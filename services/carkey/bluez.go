package main

// The car's Bluetooth link through BlueZ (D-Bus), as a vehicle-command connector.
//
// vehicle-command's own BLE connector (pkg/connector/ble) drives the controller over a raw HCI user
// channel: it takes the adapter down and holds it alone, so BlueZ, and with it the app's beacon
// scanning (docs/BEACONS.md: ATL's android.bluetooth is BlueZ over D-Bus), stops while it runs. This
// one asks BlueZ instead: discovery, the LE connection and GATT go through bluetoothd, which shares
// the controller with every other client.
//
// The wire format is the connector's (pkg/connector/ble/ble.go): the vehicle's GATT service
// 00000211-b2d1-43f0-9b88-960cebf8b91e, written on 00000212 (write with response) and indicated on
// 00000213; every message is a 2-byte big-endian length and the message, split into ATT-MTU-sized
// writes, and reassembled from indications that arrive at most rxTimeout apart.

import (
	"context"
	"crypto/sha1"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/godbus/dbus/v5"
	"github.com/teslamotors/vehicle-command/pkg/connector"
)

const (
	bluezName      = "org.bluez"
	adapterIface   = "org.bluez.Adapter1"
	deviceIface    = "org.bluez.Device1"
	gattSvcIface   = "org.bluez.GattService1"
	gattCharIface  = "org.bluez.GattCharacteristic1"
	propsIface     = "org.freedesktop.DBus.Properties"
	vehicleService = "00000211-b2d1-43f0-9b88-960cebf8b91e"
	toVehicleChar  = "00000212-b2d1-43f0-9b88-960cebf8b91e"
	fromVehicleChr = "00000213-b2d1-43f0-9b88-960cebf8b91e"

	maxMessageSize = 1024             // the connector's maxBLEMessageSize
	rxTimeout      = time.Second      // a message's chunks arrive closer than this
	maxLatency     = 4 * time.Second  // the connector's AllowedLatency
	scanTimeout    = 20 * time.Second // looking for the car's beacon
	connectTimeout = 30 * time.Second
	resolveTimeout = 20 * time.Second // GATT discovery after the connection
)

// VehicleLocalName is the name the car advertises: "S" + the first 8 bytes of SHA-1(VIN) in hex + "C"
// (pkg/connector/ble.VehicleLocalName).
func VehicleLocalName(vin string) string {
	d := sha1.Sum([]byte(vin))
	return fmt.Sprintf("S%02xC", d[:8])
}

type objects map[dbus.ObjectPath]map[string]map[string]dbus.Variant

func managedObjects(ctx context.Context, bus *dbus.Conn) (objects, error) {
	var out objects
	err := bus.Object(bluezName, "/").CallWithContext(ctx, "org.freedesktop.DBus.ObjectManager.GetManagedObjects", 0).Store(&out)
	return out, err
}

func str(props map[string]dbus.Variant, key string) string {
	if v, ok := props[key]; ok {
		if s, ok := v.Value().(string); ok {
			return s
		}
	}
	return ""
}

func path(props map[string]dbus.Variant, key string) dbus.ObjectPath {
	if v, ok := props[key]; ok {
		if p, ok := v.Value().(dbus.ObjectPath); ok {
			return p
		}
	}
	return ""
}

func boolean(props map[string]dbus.Variant, key string) bool {
	if v, ok := props[key]; ok {
		if b, ok := v.Value().(bool); ok {
			return b
		}
	}
	return false
}

// firstAdapter: BlueZ's first powered adapter (hci0 on a Pi), else its first one.
func firstAdapter(objs objects) (dbus.ObjectPath, error) {
	var all, powered []string
	for p, ifs := range objs {
		if a, ok := ifs[adapterIface]; ok {
			all = append(all, string(p))
			if boolean(a, "Powered") {
				powered = append(powered, string(p))
			}
		}
	}
	sort.Strings(all)
	sort.Strings(powered)
	if len(powered) > 0 {
		return dbus.ObjectPath(powered[0]), nil
	}
	if len(all) > 0 {
		return dbus.ObjectPath(all[0]), errors.New("the Bluetooth adapter is off")
	}
	return "", errors.New("no Bluetooth adapter")
}

// vehicleDevice: the adapter's device object that carries the car's name, and whether it was seen in
// the current discovery (BlueZ keeps an RSSI only while a discovery sees the device)
//
// Once connected, BlueZ renames the device to the car's own name (its GAP name, e.g. the name the
// driver gave the car), so a car found before is also known by its address (addr, may be empty).
func vehicleDevice(objs objects, adapter dbus.ObjectPath, name, addr string) (dbus.ObjectPath, bool) {
	for p, ifs := range objs {
		d, ok := ifs[deviceIface]
		if !ok || path(d, "Adapter") != adapter {
			continue
		}
		if str(d, "Name") == name || str(d, "Alias") == name || (addr != "" && strings.EqualFold(str(d, "Address"), addr)) {
			_, seen := d["RSSI"]
			return p, seen
		}
	}
	return "", false
}

// findVehicle runs an LE discovery (BlueZ counts it per client, next to the app's beacon scan) until
// the car's beacon is seen, at most scanTimeout. A device BlueZ already knows but did not see now is
// still returned: the car may advertise slowly, and the connection attempt tells.
func findVehicle(ctx context.Context, bus *dbus.Conn, adapter dbus.ObjectPath, name, addr string) (dbus.ObjectPath, error) {
	ad := bus.Object(bluezName, adapter)
	filter := map[string]dbus.Variant{"Transport": dbus.MakeVariant("le"), "DuplicateData": dbus.MakeVariant(false)}
	if err := ad.CallWithContext(ctx, adapterIface+".SetDiscoveryFilter", 0, filter).Err; err != nil {
		return "", fmt.Errorf("discovery filter: %w", err)
	}
	if err := ad.CallWithContext(ctx, adapterIface+".StartDiscovery", 0).Err; err != nil && !strings.Contains(err.Error(), "InProgress") {
		return "", fmt.Errorf("discovery: %w", err)
	}
	defer ad.Call(adapterIface+".StopDiscovery", 0)
	sctx, cancel := context.WithTimeout(ctx, scanTimeout)
	defer cancel()
	var known dbus.ObjectPath
	for {
		objs, err := managedObjects(sctx, bus)
		if err == nil {
			p, seen := vehicleDevice(objs, adapter, name, addr)
			if seen {
				return p, nil
			}
			known = p
		}
		select {
		case <-sctx.Done():
			if known != "" && ctx.Err() == nil {
				return known, nil
			}
			if ctx.Err() != nil {
				return "", ctx.Err()
			}
			return "", fmt.Errorf("the car's Bluetooth (%s) was not seen in %s", name, scanTimeout)
		case <-time.After(500 * time.Millisecond):
		}
	}
}

// BluezConn is one LE connection to the car, a connector.Connector.
type BluezConn struct {
	Addr   string // the car's Bluetooth address
	vin    string
	bus    *dbus.Conn // a private system-bus connection: closing it drops every match and the discovery
	dev    dbus.ObjectPath
	tx, rx dbus.ObjectPath
	block  int

	inbox  chan []byte
	sigs   chan *dbus.Signal
	done   chan struct{}
	once   sync.Once
	sendMu sync.Mutex

	rxMu   sync.Mutex
	buf    []byte
	lastRx time.Time
}

// DialBluez finds the car by its VIN's beacon name (or the address it had before, addr) and connects
// to its vehicle service. The connection's Addr is the car's address, to give the next DialBluez.
func DialBluez(ctx context.Context, vin, addr string) (*BluezConn, error) {
	bus, err := dbus.ConnectSystemBus()
	if err != nil {
		return nil, fmt.Errorf("system bus: %w", err)
	}
	c := &BluezConn{vin: vin, Addr: addr, bus: bus, inbox: make(chan []byte, connector.BufferSize), done: make(chan struct{})}
	if err := c.open(ctx); err != nil {
		c.Close()
		return nil, err
	}
	return c, nil
}

func (c *BluezConn) open(ctx context.Context) error {
	objs, err := managedObjects(ctx, c.bus)
	if err != nil {
		return fmt.Errorf("BlueZ: %w", err)
	}
	adapter, err := firstAdapter(objs)
	if err != nil {
		return err
	}
	name := VehicleLocalName(c.vin)
	c.dev, err = findVehicle(ctx, c.bus, adapter, name, c.Addr)
	if err != nil {
		return err
	}
	dev := c.bus.Object(bluezName, c.dev)
	if v, err := dev.GetProperty(deviceIface + ".Address"); err == nil {
		if a, ok := v.Value().(string); ok {
			c.Addr = a
		}
	}
	cctx, cancel := context.WithTimeout(ctx, connectTimeout)
	err = dev.CallWithContext(cctx, deviceIface+".Connect", 0).Err
	cancel()
	if err != nil && !strings.Contains(err.Error(), "AlreadyConnected") {
		return fmt.Errorf("connecting to the car: %w", err)
	}
	// GATT discovery: ServicesResolved, then the vehicle service's two characteristics
	rctx, cancel2 := context.WithTimeout(ctx, resolveTimeout)
	defer cancel2()
	for {
		v, err := dev.GetProperty(deviceIface + ".ServicesResolved")
		if err == nil {
			if b, ok := v.Value().(bool); ok && b {
				break
			}
		}
		select {
		case <-rctx.Done():
			return errors.New("the car's Bluetooth services did not resolve")
		case <-time.After(200 * time.Millisecond):
		}
	}
	if objs, err = managedObjects(ctx, c.bus); err != nil {
		return fmt.Errorf("BlueZ: %w", err)
	}
	var svc dbus.ObjectPath
	for p, ifs := range objs {
		if s, ok := ifs[gattSvcIface]; ok && path(s, "Device") == c.dev && strings.EqualFold(str(s, "UUID"), vehicleService) {
			svc = p
		}
	}
	if svc == "" {
		return errors.New("the car has no vehicle service (not a Tesla, or its Bluetooth is busy)")
	}
	mtu := 23
	for p, ifs := range objs {
		ch, ok := ifs[gattCharIface]
		if !ok || path(ch, "Service") != svc {
			continue
		}
		switch strings.ToLower(str(ch, "UUID")) {
		case toVehicleChar:
			c.tx = p
			if v, ok := ch["MTU"]; ok {
				if m, ok := v.Value().(uint16); ok && m >= 23 {
					mtu = int(m)
				}
			}
		case fromVehicleChr:
			c.rx = p
		}
	}
	if c.tx == "" || c.rx == "" {
		return errors.New("the car's vehicle service lacks its characteristics")
	}
	c.block = min(mtu, maxMessageSize) - 3

	// indications on rx, and the device's Connected going false
	for _, p := range []dbus.ObjectPath{c.rx, c.dev} {
		if err := c.bus.AddMatchSignal(dbus.WithMatchObjectPath(p), dbus.WithMatchInterface(propsIface),
			dbus.WithMatchMember("PropertiesChanged")); err != nil {
			return fmt.Errorf("signal match: %w", err)
		}
	}
	c.sigs = make(chan *dbus.Signal, 64)
	c.bus.Signal(c.sigs)
	go c.listen()
	if err := c.bus.Object(bluezName, c.rx).CallWithContext(ctx, gattCharIface+".StartNotify", 0).Err; err != nil {
		return fmt.Errorf("subscribing to the car: %w", err)
	}
	return nil
}

func (c *BluezConn) listen() {
	for {
		select {
		case <-c.done:
			return
		case s, ok := <-c.sigs:
			if !ok {
				c.Close()
				return
			}
			if len(s.Body) < 2 {
				continue
			}
			changed, ok := s.Body[1].(map[string]dbus.Variant)
			if !ok {
				continue
			}
			switch s.Path {
			case c.rx:
				if v, ok := changed["Value"]; ok {
					if b, ok := v.Value().([]byte); ok {
						c.receive(b)
					}
				}
			case c.dev:
				if v, ok := changed["Connected"]; ok {
					if b, ok := v.Value().(bool); ok && !b {
						c.Close()
						return
					}
				}
			}
		}
	}
}

// receive reassembles messages: a 2-byte length, then that many bytes, across indications
func (c *BluezConn) receive(p []byte) {
	c.rxMu.Lock()
	defer c.rxMu.Unlock()
	if time.Since(c.lastRx) > rxTimeout {
		c.buf = nil
	}
	c.lastRx = time.Now()
	c.buf = append(c.buf, p...)
	for len(c.buf) >= 2 {
		n := int(c.buf[0])<<8 | int(c.buf[1])
		if n > maxMessageSize {
			c.buf = nil
			return
		}
		if len(c.buf) < 2+n {
			return
		}
		msg := append([]byte(nil), c.buf[2:2+n]...)
		c.buf = c.buf[2+n:]
		select {
		case c.inbox <- msg:
		default: // the dispatcher is behind: dropped, as the connector does
		}
	}
}

// Done is closed when the link is gone (the car went out of range, or Close).
func (c *BluezConn) Done() <-chan struct{} { return c.done }

func (c *BluezConn) Receive() <-chan []byte { return c.inbox }

func (c *BluezConn) Send(ctx context.Context, buffer []byte) error {
	if len(buffer) > maxMessageSize {
		return fmt.Errorf("message of %d bytes is too long", len(buffer))
	}
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	select {
	case <-c.done:
		return errors.New("the car's Bluetooth link is closed")
	default:
	}
	out := append([]byte{byte(len(buffer) >> 8), byte(len(buffer))}, buffer...)
	tx := c.bus.Object(bluezName, c.tx)
	opts := map[string]dbus.Variant{"type": dbus.MakeVariant("request")}
	for len(out) > 0 {
		n := min(c.block, len(out))
		if err := tx.CallWithContext(ctx, gattCharIface+".WriteValue", 0, out[:n], opts).Err; err != nil {
			return fmt.Errorf("writing to the car: %w", err)
		}
		out = out[n:]
	}
	return nil
}

func (c *BluezConn) VIN() string { return c.vin }

func (c *BluezConn) Close() {
	c.once.Do(func() {
		close(c.done)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if c.rx != "" {
			c.bus.Object(bluezName, c.rx).CallWithContext(ctx, gattCharIface+".StopNotify", 0)
		}
		if c.dev != "" {
			c.bus.Object(bluezName, c.dev).CallWithContext(ctx, deviceIface+".Disconnect", 0)
		}
		c.bus.Close()
	})
}

func (c *BluezConn) PreferredAuthMethod() connector.AuthMethod { return connector.AuthMethodGCM }
func (c *BluezConn) RetryInterval() time.Duration              { return time.Second }
func (c *BluezConn) AllowedLatency() time.Duration             { return maxLatency }
