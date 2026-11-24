// teslalinux-carkey: the car's own state over Bluetooth, with a read-only key.
//
// The device holds a key the car knows (role vehicle_monitor: it reads the car's state; it cannot
// unlock, open or drive), added by the driver's key card tap (POST /pair, the car's own add-key
// prompt). While a VIN and the key are set, this keeps one Bluetooth LE link to the car through BlueZ
// (bluez.go) and an infotainment session on it, and reads the car's drive state every DRIVE_EVERY
// (the route the car's own navigation follows: destination, its coordinates, time and distance to
// it) and its location (the car's GPS) every second while the car's position is used for the app
// (carsync.json "carGps"), every 5 s otherwise.
//
// The rest of the device reads it here, on loopback (services/carsync/carsync.py follows the car's
// destination into the app and feeds the car's position; services/api/api.py forwards /api/carLink):
//
//	GET  /state   {"vin", "keyReady", "link": "off"|"searching"|"connected"|"notPaired"|"asleep"|"error",
//	               "message", "pairRequestedAt", "connectedAt", "drive": {...}|null, "location": {...}|null}
//	POST /vin     {"vin": "..."}: the car to link with (17 characters); a key is made the first time
//	POST /pair    sends the car the add-key request; the driver taps the key card on the console
//	POST /forget  forgets the VIN and the key (the car keeps listing the key under Locks until the
//	              driver removes it there)
//
// The VIN and the key live in TESLALINUX_CARKEY_DIR (root only). This runs as root for that, and for
// BlueZ's GATT client.
package main

import (
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/teslamotors/vehicle-command/pkg/protocol"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/carserver"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/keys"
	universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/vcsec"
	"github.com/teslamotors/vehicle-command/pkg/vehicle"
)

var (
	stateDir     = env("TESLALINUX_CARKEY_DIR", "/var/lib/teslalinux/carkey")
	bind         = env("TESLALINUX_CARKEY_BIND", "127.0.0.1:8086")
	syncSettings = env("TESLALINUX_CARSYNC_SETTINGS", "/var/lib/teslalinux/state/carsync.json")
	vinRe        = regexp.MustCompile(`^[A-HJ-NPR-Z0-9]{17}$`)
)

const (
	driveEvery      = 2 * time.Second
	locationFast    = time.Second
	locationSlow    = 5 * time.Second
	sessionTimeout  = 15 * time.Second
	readTimeout     = 8 * time.Second
	maxReadFailures = 3
	mphToKph        = 1.609344
	milesToKm       = 1.609344
)

func env(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

type Drive struct {
	At              int64    `json:"at"`
	Gear            string   `json:"gear"`
	SpeedKph        float64  `json:"speedKph"`
	Destination     string   `json:"destination"`
	DestLat         *float64 `json:"destLat"`
	DestLon         *float64 `json:"destLon"`
	MinutesToArrive *float64 `json:"minutesToArrival"`
	KmToArrive      *float64 `json:"kmToArrival"`
	TrafficDelayMin *float64 `json:"trafficDelayMinutes"`
	EnergyAtArrival *float64 `json:"energyAtArrival"`
}

type Location struct {
	At       int64   `json:"at"`
	Lat      float64 `json:"lat"`
	Lon      float64 `json:"lon"`
	Heading  float64 `json:"heading"`
	Valid    bool    `json:"valid"`
	CarAt    int64   `json:"carAt"` // the car's own timestamp of the fix (ms), 0 when it gave none
	Accuracy float64 `json:"accuracy"`
}

type State struct {
	VIN             string    `json:"vin"`
	KeyReady        bool      `json:"keyReady"`
	Link            string    `json:"link"`
	Message         string    `json:"message"`
	PairRequestedAt int64     `json:"pairRequestedAt"`
	ConnectedAt     int64     `json:"connectedAt"`
	Drive           *Drive    `json:"drive"`
	Location        *Location `json:"location"`
}

type daemon struct {
	mu      sync.Mutex
	st      State
	car     *vehicle.Vehicle // while a link is up
	paired  bool             // the session started: the car knows the key
	carMu   sync.Mutex       // one request to the car at a time
	reconf  chan struct{}    // the VIN or key changed
	pairNow chan struct{}    // a pair request went out: retry the session soon
}

func (d *daemon) set(link, msg string) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.st.Link != link || d.st.Message != msg {
		log.Printf("link %s: %s", link, msg)
	}
	d.st.Link, d.st.Message = link, msg
}

func (d *daemon) snapshot() State {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.st
}

func vinPath() string { return filepath.Join(stateDir, "vin") }
func keyPath() string { return filepath.Join(stateDir, "private.pem") }

// the car's Bluetooth address, once found (BlueZ shows the car under its own name after a connection)
func addrPath() string { return filepath.Join(stateDir, "address") }

func readVIN() string {
	b, err := os.ReadFile(vinPath())
	if err != nil {
		return ""
	}
	v := strings.TrimSpace(string(b))
	if !vinRe.MatchString(v) {
		return ""
	}
	return v
}

// ensureKey loads the key, or makes one (P-256, SEC1 PEM as tesla-keygen writes it)
func ensureKey() (protocol.ECDHPrivateKey, error) {
	if k, err := protocol.LoadPrivateKey(keyPath()); err == nil {
		return k, nil
	}
	ek, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalECPrivateKey(ek)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return nil, err
	}
	tmp := keyPath() + ".tmp"
	if err := os.WriteFile(tmp, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp, keyPath()); err != nil {
		return nil, err
	}
	log.Printf("made a new key")
	return protocol.LoadPrivateKey(keyPath())
}

func loadKey() protocol.ECDHPrivateKey {
	k, err := protocol.LoadPrivateKey(keyPath())
	if err != nil {
		return nil
	}
	return k
}

// carGps: whether the car's position feeds the app (carsync.json, written by the API)
func carGps() bool {
	b, err := os.ReadFile(syncSettings)
	if err != nil {
		return false
	}
	var s struct {
		CarGps bool `json:"carGps"`
	}
	return json.Unmarshal(b, &s) == nil && s.CarGps
}

// wait sleeps for dur, or less when the config changes or a pair request went out
func (d *daemon) wait(ctx context.Context, dur time.Duration) {
	select {
	case <-ctx.Done():
	case <-d.reconf:
		// put it back for the loop to see
		select {
		case d.reconf <- struct{}{}:
		default:
		}
	case <-d.pairNow:
	case <-time.After(dur):
	}
}

func (d *daemon) reconfigured() bool {
	select {
	case <-d.reconf:
		return true
	default:
		return false
	}
}

func (d *daemon) run(ctx context.Context) {
	backoff := 10 * time.Second
	for ctx.Err() == nil {
		d.reconfigured()
		vin, key := readVIN(), loadKey()
		d.mu.Lock()
		d.st.VIN, d.st.KeyReady = vin, key != nil
		d.mu.Unlock()
		if vin == "" || key == nil {
			d.set("off", "No car set up")
			select {
			case <-ctx.Done():
			case <-d.reconf:
			}
			continue
		}
		d.set("searching", "Looking for the car over Bluetooth")
		dctx, cancel := context.WithTimeout(ctx, scanTimeout+connectTimeout+resolveTimeout)
		addr, _ := os.ReadFile(addrPath())
		conn, err := DialBluez(dctx, vin, strings.TrimSpace(string(addr)))
		cancel()
		if err == nil && conn.Addr != "" && conn.Addr != strings.TrimSpace(string(addr)) {
			_ = os.WriteFile(addrPath(), []byte(conn.Addr+"\n"), 0o600)
		}
		if err != nil {
			d.set("searching", "The car was not reached over Bluetooth: "+err.Error())
			d.wait(ctx, backoff)
			backoff = min(backoff*2, time.Minute)
			continue
		}
		car, err := vehicle.NewVehicle(conn, key, nil)
		if err == nil {
			err = car.Connect(ctx)
		}
		if err != nil {
			conn.Close()
			d.set("error", "The car's link did not start: "+err.Error())
			d.wait(ctx, backoff)
			continue
		}
		backoff = 10 * time.Second
		d.mu.Lock()
		d.car, d.paired = car, false
		d.st.ConnectedAt = time.Now().Unix()
		d.mu.Unlock()
		d.session(ctx, car, conn)
		d.mu.Lock()
		d.car, d.paired = nil, false
		d.st.ConnectedAt = 0
		d.mu.Unlock()
		car.Disconnect()
		conn.Close()
	}
}

func notPaired(err error) bool {
	return errors.Is(err, protocol.ErrKeyNotPaired) || strings.Contains(err.Error(), "not been paired")
}

// session keeps the infotainment session and reads until the link drops or the config changes
func (d *daemon) session(ctx context.Context, car *vehicle.Vehicle, conn *BluezConn) {
	failures := 0
	var lastDrive, lastLoc time.Time
	started := false
	for ctx.Err() == nil {
		select {
		case <-conn.Done():
			d.set("searching", "The car's Bluetooth link dropped")
			return
		default:
		}
		if d.reconfigured() {
			return
		}
		if !started {
			sctx, cancel := context.WithTimeout(ctx, sessionTimeout)
			d.carMu.Lock()
			err := car.StartSession(sctx, []universal.Domain{universal.Domain_DOMAIN_INFOTAINMENT})
			d.carMu.Unlock()
			cancel()
			switch {
			case err == nil:
				started, failures = true, 0
				d.mu.Lock()
				d.paired, d.st.PairRequestedAt = true, 0
				d.mu.Unlock()
				d.set("connected", "Reading the car")
			case notPaired(err):
				d.set("notPaired", "The car does not know this device's key yet")
				d.wait(ctx, 5*time.Second)
			default:
				failures++
				d.set("asleep", "The car did not answer (it may be asleep): "+err.Error())
				if failures >= maxReadFailures {
					return
				}
				d.wait(ctx, 10*time.Second)
			}
			continue
		}
		now := time.Now()
		locEvery := locationSlow
		if carGps() {
			locEvery = locationFast
		}
		var err error
		did := false
		if now.Sub(lastDrive) >= driveEvery {
			err = d.readDrive(ctx, car)
			lastDrive, did = now, true
		}
		if err == nil && now.Sub(lastLoc) >= locEvery {
			err = d.readLocation(ctx, car)
			lastLoc, did = now, true
		}
		if err != nil {
			failures++
			d.set("asleep", "The car stopped answering: "+err.Error())
			if failures >= maxReadFailures {
				return
			}
			started = false // a sleeping car drops the session: start it again
			d.wait(ctx, 5*time.Second)
			continue
		}
		if did {
			if failures > 0 {
				d.set("connected", "Reading the car")
			}
			failures = 0
		}
		select {
		case <-ctx.Done():
		case <-conn.Done():
		case <-time.After(200 * time.Millisecond):
		}
	}
}

func f64(x float32) *float64 {
	v := float64(x)
	return &v
}

func gear(s *carserver.ShiftState) string {
	switch {
	case s == nil:
		return ""
	case s.GetP() != nil:
		return "P"
	case s.GetR() != nil:
		return "R"
	case s.GetN() != nil:
		return "N"
	case s.GetD() != nil:
		return "D"
	}
	return ""
}

func (d *daemon) readDrive(ctx context.Context, car *vehicle.Vehicle) error {
	rctx, cancel := context.WithTimeout(ctx, readTimeout)
	defer cancel()
	d.carMu.Lock()
	data, err := car.GetState(rctx, vehicle.StateCategoryDrive)
	d.carMu.Unlock()
	if err != nil {
		return err
	}
	ds := data.GetDriveState()
	if ds == nil {
		return errors.New("no drive state in the answer")
	}
	dr := &Drive{At: time.Now().Unix(), Gear: gear(ds.GetShiftState()), SpeedKph: float64(ds.GetSpeedFloat()) * mphToKph,
		Destination: ds.GetActiveRouteDestination()}
	// a route is active while it has a name or time left: the car keeps the last route's coordinates
	// after it ends (seen: no name, 0 minutes, the old destination's point)
	if dr.Destination != "" || ds.GetActiveRouteMinutesToArrival() > 0 {
		if c := ds.GetActiveRouteCoordinates(); c != nil && (c.GetLatitude() != 0 || c.GetLongitude() != 0) {
			dr.DestLat, dr.DestLon = f64(c.GetLatitude()), f64(c.GetLongitude())
		}
		dr.MinutesToArrive = f64(ds.GetActiveRouteMinutesToArrival())
		km := float64(ds.GetActiveRouteMilesToArrival()) * milesToKm
		dr.KmToArrive = &km
		dr.TrafficDelayMin = f64(ds.GetActiveRouteTrafficMinutesDelay())
		dr.EnergyAtArrival = f64(ds.GetActiveRouteEnergyAtArrival())
	}
	d.mu.Lock()
	d.st.Drive = dr
	d.mu.Unlock()
	return nil
}

func (d *daemon) readLocation(ctx context.Context, car *vehicle.Vehicle) error {
	rctx, cancel := context.WithTimeout(ctx, readTimeout)
	defer cancel()
	d.carMu.Lock()
	data, err := car.GetState(rctx, vehicle.StateCategoryLocation)
	d.carMu.Unlock()
	if err != nil {
		return err
	}
	ls := data.GetLocationState()
	if ls == nil {
		return errors.New("no location state in the answer")
	}
	loc := &Location{At: time.Now().Unix(), Lat: float64(ls.GetLatitude()), Lon: float64(ls.GetLongitude()),
		Heading: float64(ls.GetHeading()), Valid: ls.GetEstimatedGpsValid(), Accuracy: float64(ls.GetGeoAccuracy())}
	if ls.GetNativeLocationSupported() && (ls.GetNativeLatitude() != 0 || ls.GetNativeLongitude() != 0) {
		loc.Lat, loc.Lon = float64(ls.GetNativeLatitude()), float64(ls.GetNativeLongitude())
	}
	if ts := ls.GetTimestamp(); ts != nil {
		loc.CarAt = ts.AsTime().UnixMilli()
	}
	if loc.Lat == 0 && loc.Lon == 0 {
		loc.Valid = false
	}
	d.mu.Lock()
	d.st.Location = loc
	d.mu.Unlock()
	return nil
}

func (d *daemon) pair(ctx context.Context) (string, error) {
	key := loadKey()
	if key == nil || readVIN() == "" {
		return "", errors.New("set the car's VIN first")
	}
	d.mu.Lock()
	car, paired := d.car, d.paired
	d.mu.Unlock()
	if paired {
		return "The car already knows this device's key.", nil
	}
	if car == nil {
		return "", errors.New("the car is not connected over Bluetooth yet: sit in the car with it on, and try again in a moment")
	}
	pub, err := ecdh.P256().NewPublicKey(key.PublicBytes())
	if err != nil {
		return "", err
	}
	pctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	d.carMu.Lock()
	err = car.SendAddKeyRequestWithRole(pctx, pub, keys.Role_ROLE_VEHICLE_MONITOR, vcsec.KeyFormFactor_KEY_FORM_FACTOR_ANDROID_DEVICE)
	d.carMu.Unlock()
	if err != nil {
		return "", fmt.Errorf("the car did not take the request: %w", err)
	}
	d.mu.Lock()
	d.st.PairRequestedAt = time.Now().Unix()
	d.mu.Unlock()
	select {
	case d.pairNow <- struct{}{}:
	default:
	}
	log.Printf("sent the add-key request")
	return "Sent. Tap your key card on the reader behind the cupholders, then confirm on the car's screen.", nil
}

func (d *daemon) setVIN(vin string) error {
	vin = strings.ToUpper(strings.TrimSpace(vin))
	if !vinRe.MatchString(vin) {
		return errors.New("a VIN has 17 letters and digits (no I, O or Q)")
	}
	if _, err := ensureKey(); err != nil {
		return err
	}
	if old := readVIN(); old != vin {
		_ = os.Remove(addrPath()) // another car
	}
	tmp := vinPath() + ".tmp"
	if err := os.WriteFile(tmp, []byte(vin+"\n"), 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, vinPath()); err != nil {
		return err
	}
	d.kick()
	return nil
}

func (d *daemon) forget() error {
	for _, p := range []string{vinPath(), keyPath(), addrPath()} {
		if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
	}
	d.mu.Lock()
	d.st.Drive, d.st.Location, d.st.PairRequestedAt = nil, nil, 0
	d.mu.Unlock()
	d.kick()
	return nil
}

func (d *daemon) kick() {
	select {
	case d.reconf <- struct{}{}:
	default:
	}
}

func reply(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func (d *daemon) routes(ctx context.Context) *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /state", func(w http.ResponseWriter, _ *http.Request) {
		reply(w, 200, d.snapshot())
	})
	mux.HandleFunc("POST /vin", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			VIN string `json:"vin"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
			reply(w, 400, map[string]any{"ok": false, "message": "bad request"})
			return
		}
		if err := d.setVIN(req.VIN); err != nil {
			reply(w, 400, map[string]any{"ok": false, "message": err.Error()})
			return
		}
		reply(w, 200, map[string]any{"ok": true, "message": "Saved"})
	})
	mux.HandleFunc("POST /pair", func(w http.ResponseWriter, _ *http.Request) {
		msg, err := d.pair(ctx)
		if err != nil {
			reply(w, 409, map[string]any{"ok": false, "message": err.Error()})
			return
		}
		reply(w, 200, map[string]any{"ok": true, "message": msg})
	})
	mux.HandleFunc("POST /forget", func(w http.ResponseWriter, _ *http.Request) {
		if err := d.forget(); err != nil {
			reply(w, 500, map[string]any{"ok": false, "message": err.Error()})
			return
		}
		reply(w, 200, map[string]any{"ok": true, "message": "Forgotten"})
	})
	return mux
}

func main() {
	log.SetFlags(0)
	log.SetPrefix("teslalinux-carkey: ")
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		log.Fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	d := &daemon{reconf: make(chan struct{}, 1), pairNow: make(chan struct{}, 1), st: State{Link: "off"}}
	srv := &http.Server{Addr: bind, Handler: d.routes(ctx), ReadHeaderTimeout: 5 * time.Second}
	go func() {
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Fatal(err)
		}
	}()
	d.run(ctx)
	sctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_ = srv.Shutdown(sctx)
}
