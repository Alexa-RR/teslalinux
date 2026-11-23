/* mock-bluez.c: a BlueZ (bluetoothd) for BluetoothTest, on a private system bus
 * (DBUS_SYSTEM_BUS_ADDRESS). It owns org.bluez and answers what ATL's Bluetooth host side
 * (android_bluetooth_TlBluez.c) asks: ObjectManager.GetManagedObjects at "/" (one adapter,
 * /org/bluez/hci0, and the devices it found), the adapter's properties (Properties.Get/GetAll/Set,
 * Set Powered emits PropertiesChanged) and its SetDiscoveryFilter, StartDiscovery and
 * StopDiscovery (as BlueZ: NotReady while powered off, InProgress for a second start).
 * Commands from the control FIFO (argv[1]), one per line, play what BlueZ signals:
 *   found ADDR public|random RSSI [NAME]   a new device (InterfacesAdded with RSSI, flags, name)
 *   sd ADDR RSSI UUID HEX                  an advertisement with service data (PropertiesChanged)
 *   md ADDR RSSI COMPANY HEX               an advertisement with manufacturer data
 *   uuids ADDR UUID[,UUID...]              the device's service UUIDs
 *   bond ADDR                              the device is bonded
 *   gone ADDR                              the device is removed (InterfacesRemoved)
 *   powered 0|1                            the adapter powers off/on
 *   remove_adapter | add_adapter           the adapter goes / comes back (USB unplug / plug)
 *   quit_bluez | own_bluez                 bluetoothd stops / starts (the name's owner)
 * After each command and each adapter method call it prints a line on stdout ("ok COMMAND",
 * "call SetDiscoveryFilter transport=le duplicate=1 uuids=-", "call StartDiscovery ok", ...).
 * Like BlueZ it signals every device; it does not apply the filter's UUIDs itself.
 * Build: gcc -o mock-bluez mock-bluez.c $(pkg-config --cflags --libs gio-2.0)
 * Never part of the runtime. */
#include <fcntl.h>
#include <gio/gio.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define ADAPTER "/org/bluez/hci0"
#define ADAPTER_ADDRESS "AA:BB:CC:DD:EE:01"

static GDBusConnection *bus;
static guint owner;
static gboolean powered = TRUE, adapter_present = TRUE, discovering;
static guint adapter_reg;
static GHashTable *devices; /* path -> GVariant a{sv} (the Device1 properties) */
static GDBusNodeInfo *info;

static const char *xml =
	"<node>"
	"<interface name='org.freedesktop.DBus.ObjectManager'>"
	"<method name='GetManagedObjects'><arg type='a{oa{sa{sv}}}' direction='out'/></method>"
	"</interface>"
	"<interface name='org.bluez.Adapter1'>"
	"<method name='SetDiscoveryFilter'><arg type='a{sv}' direction='in'/></method>"
	"<method name='StartDiscovery'/><method name='StopDiscovery'/>"
	"<property name='Address' type='s' access='read'/><property name='Alias' type='s' access='readwrite'/>"
	"<property name='Name' type='s' access='read'/><property name='Powered' type='b' access='readwrite'/>"
	"<property name='Discovering' type='b' access='read'/>"
	"</interface>"
	"</node>";

static void say(const char *fmt, ...)
{
	va_list ap;
	va_start(ap, fmt);
	vprintf(fmt, ap);
	va_end(ap);
	printf("\n");
	fflush(stdout);
}

static char *dev_path(const char *addr)
{
	char *p = g_strdup_printf(ADAPTER "/dev_%s", addr);
	for (char *c = p + strlen(ADAPTER) + 5; *c; c++)
		if (*c == ':')
			*c = '_';
	return p;
}

static GVariant *adapter_props(void)
{
	GVariantBuilder b;
	g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
	g_variant_builder_add(&b, "{sv}", "Address", g_variant_new_string(ADAPTER_ADDRESS));
	g_variant_builder_add(&b, "{sv}", "Alias", g_variant_new_string("tl-mock"));
	g_variant_builder_add(&b, "{sv}", "Name", g_variant_new_string("tl-mock"));
	g_variant_builder_add(&b, "{sv}", "Powered", g_variant_new_boolean(powered));
	g_variant_builder_add(&b, "{sv}", "Discovering", g_variant_new_boolean(discovering));
	return g_variant_builder_end(&b);
}

static void emit_changed(const char *path, const char *iface, GVariant *changed)
{
	g_dbus_connection_emit_signal(bus, NULL, path, "org.freedesktop.DBus.Properties", "PropertiesChanged",
	                              g_variant_new("(s@a{sv}as)", iface, changed, NULL), NULL);
	g_dbus_connection_flush_sync(bus, NULL, NULL);
}

static void emit_one(const char *path, const char *iface, const char *prop, GVariant *value)
{
	GVariantBuilder b;
	g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
	g_variant_builder_add(&b, "{sv}", prop, value);
	emit_changed(path, iface, g_variant_builder_end(&b));
}

static void method_call(GDBusConnection *c, const char *sender, const char *path, const char *iface, const char *method,
                        GVariant *params, GDBusMethodInvocation *inv, gpointer data)
{
	if (!strcmp(method, "GetManagedObjects")) {
		GVariantBuilder objects;
		g_variant_builder_init(&objects, G_VARIANT_TYPE("a{oa{sa{sv}}}"));
		if (adapter_present) {
			GVariantBuilder ifaces;
			g_variant_builder_init(&ifaces, G_VARIANT_TYPE("a{sa{sv}}"));
			g_variant_builder_add(&ifaces, "{s@a{sv}}", "org.bluez.Adapter1", adapter_props());
			g_variant_builder_add(&objects, "{o@a{sa{sv}}}", ADAPTER, g_variant_builder_end(&ifaces));
			GHashTableIter it;
			gpointer k, v;
			g_hash_table_iter_init(&it, devices);
			while (g_hash_table_iter_next(&it, &k, &v)) {
				g_variant_builder_init(&ifaces, G_VARIANT_TYPE("a{sa{sv}}"));
				g_variant_builder_add(&ifaces, "{s@a{sv}}", "org.bluez.Device1", (GVariant *)v);
				g_variant_builder_add(&objects, "{o@a{sa{sv}}}", (const char *)k, g_variant_builder_end(&ifaces));
			}
		}
		g_dbus_method_invocation_return_value(inv, g_variant_new("(a{oa{sa{sv}}})", &objects));
		return;
	}
	if (!strcmp(method, "SetDiscoveryFilter")) {
		GVariant *filter = g_variant_get_child_value(params, 0);
		const char *transport = "auto";
		gboolean dup = FALSE;
		g_variant_lookup(filter, "Transport", "&s", &transport);
		g_variant_lookup(filter, "DuplicateData", "b", &dup);
		const char **uuids = NULL;
		g_variant_lookup(filter, "UUIDs", "^a&s", &uuids);
		char *joined = uuids && *uuids ? g_strjoinv(",", (char **)uuids) : g_strdup("-");
		say("call SetDiscoveryFilter transport=%s duplicate=%d uuids=%s", transport, dup, joined);
		g_free(joined);
		g_free(uuids);
		g_variant_unref(filter);
		g_dbus_method_invocation_return_value(inv, NULL);
		return;
	}
	if (!strcmp(method, "StartDiscovery")) {
		if (!powered) {
			say("call StartDiscovery NotReady");
			g_dbus_method_invocation_return_dbus_error(inv, "org.bluez.Error.NotReady", "Resource Not Ready");
		} else if (discovering) {
			say("call StartDiscovery InProgress");
			g_dbus_method_invocation_return_dbus_error(inv, "org.bluez.Error.InProgress", "Operation already in progress");
		} else {
			discovering = TRUE;
			say("call StartDiscovery ok");
			g_dbus_method_invocation_return_value(inv, NULL);
			emit_one(ADAPTER, "org.bluez.Adapter1", "Discovering", g_variant_new_boolean(TRUE));
		}
		return;
	}
	if (!strcmp(method, "StopDiscovery")) {
		if (!discovering) {
			say("call StopDiscovery NotReady");
			g_dbus_method_invocation_return_dbus_error(inv, "org.bluez.Error.Failed", "No discovery started");
		} else {
			discovering = FALSE;
			say("call StopDiscovery ok");
			g_dbus_method_invocation_return_value(inv, NULL);
			emit_one(ADAPTER, "org.bluez.Adapter1", "Discovering", g_variant_new_boolean(FALSE));
		}
		return;
	}
	g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", method);
}

static GVariant *get_property(GDBusConnection *c, const char *sender, const char *path, const char *iface,
                              const char *prop, GError **error, gpointer data)
{
	GVariant *all = adapter_props(), *v = g_variant_lookup_value(all, prop, NULL);
	g_variant_unref(g_variant_ref_sink(all));
	if (!v)
		g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "no property %s", prop);
	return v;
}

static gboolean set_property(GDBusConnection *c, const char *sender, const char *path, const char *iface,
                             const char *prop, GVariant *value, GError **error, gpointer data)
{
	if (!strcmp(prop, "Powered")) {
		powered = g_variant_get_boolean(value);
		if (!powered)
			discovering = FALSE;
		say("call Set Powered=%d", powered);
		emit_one(ADAPTER, "org.bluez.Adapter1", "Powered", g_variant_new_boolean(powered));
		return TRUE;
	}
	g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_PROPERTY_READ_ONLY, "read-only %s", prop);
	return FALSE;
}

static const GDBusInterfaceVTable vtable = {method_call, get_property, set_property};

static void register_adapter(void)
{
	GError *err = NULL;
	adapter_reg = g_dbus_connection_register_object(bus, ADAPTER, g_dbus_node_info_lookup_interface(info, "org.bluez.Adapter1"),
	                                                &vtable, NULL, NULL, &err);
	if (!adapter_reg) {
		fprintf(stderr, "mock-bluez: %s\n", err->message);
		exit(1);
	}
}

/* a{sv} merge: the device's properties with the new ones */
static GVariant *merge(GVariant *old, GVariant *changed)
{
	GVariantBuilder b;
	g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
	GVariantIter it;
	const char *k;
	GVariant *v;
	if (old) {
		g_variant_iter_init(&it, old);
		while (g_variant_iter_next(&it, "{&sv}", &k, &v)) {
			if (!g_variant_lookup_value(changed, k, NULL))
				g_variant_builder_add(&b, "{sv}", k, v);
			g_variant_unref(v);
		}
	}
	g_variant_iter_init(&it, changed);
	while (g_variant_iter_next(&it, "{&sv}", &k, &v)) {
		g_variant_builder_add(&b, "{sv}", k, v);
		g_variant_unref(v);
	}
	return g_variant_ref_sink(g_variant_builder_end(&b));
}

static GVariant *bytes_from_hex(const char *hex)
{
	GByteArray *a = g_byte_array_new();
	for (const char *p = hex; p[0] && p[1]; p += 2) {
		guint8 x = (g_ascii_xdigit_value(p[0]) << 4) | g_ascii_xdigit_value(p[1]);
		g_byte_array_append(a, &x, 1);
	}
	GVariant *v = g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, a->data, a->len, 1);
	g_byte_array_free(a, TRUE);
	return v;
}

static void device_update(const char *addr, GVariant *changed)
{
	char *path = dev_path(addr);
	GVariant *old = g_hash_table_lookup(devices, path);
	g_variant_ref_sink(changed);
	g_hash_table_replace(devices, g_strdup(path), merge(old, changed));
	emit_changed(path, "org.bluez.Device1", changed);
	g_variant_unref(changed);
	g_free(path);
}

static void command(char *line)
{
	char **a = g_strsplit(line, " ", 6);
	int n = g_strv_length(a);
	const char *cmd = a[0];
	if (!strcmp(cmd, "found") && n >= 4) {
		char *path = dev_path(a[1]);
		GVariantBuilder b;
		g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
		g_variant_builder_add(&b, "{sv}", "Address", g_variant_new_string(a[1]));
		g_variant_builder_add(&b, "{sv}", "AddressType", g_variant_new_string(a[2]));
		g_variant_builder_add(&b, "{sv}", "Adapter", g_variant_new_object_path(ADAPTER));
		g_variant_builder_add(&b, "{sv}", "RSSI", g_variant_new_int16(atoi(a[3])));
		g_variant_builder_add(&b, "{sv}", "Paired", g_variant_new_boolean(FALSE));
		g_variant_builder_add(&b, "{sv}", "Bonded", g_variant_new_boolean(FALSE));
		guint8 flags = 0x06;
		g_variant_builder_add(&b, "{sv}", "AdvertisingFlags", g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, &flags, 1, 1));
		if (n >= 5) {
			g_variant_builder_add(&b, "{sv}", "Name", g_variant_new_string(a[4]));
			g_variant_builder_add(&b, "{sv}", "Alias", g_variant_new_string(a[4]));
		}
		GVariant *props = g_variant_ref_sink(g_variant_builder_end(&b));
		g_hash_table_replace(devices, g_strdup(path), g_variant_ref(props));
		GVariantBuilder ifaces;
		g_variant_builder_init(&ifaces, G_VARIANT_TYPE("a{sa{sv}}"));
		g_variant_builder_add(&ifaces, "{s@a{sv}}", "org.bluez.Device1", props);
		g_dbus_connection_emit_signal(bus, NULL, "/", "org.freedesktop.DBus.ObjectManager", "InterfacesAdded",
		                              g_variant_new("(oa{sa{sv}})", path, &ifaces), NULL);
		g_dbus_connection_flush_sync(bus, NULL, NULL);
		g_variant_unref(props);
		g_free(path);
	} else if (!strcmp(cmd, "sd") && n >= 5) {
		GVariantBuilder sd, b;
		g_variant_builder_init(&sd, G_VARIANT_TYPE("a{sv}"));
		g_variant_builder_add(&sd, "{sv}", a[3], bytes_from_hex(a[4]));
		g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
		g_variant_builder_add(&b, "{sv}", "RSSI", g_variant_new_int16(atoi(a[2])));
		g_variant_builder_add(&b, "{sv}", "ServiceData", g_variant_builder_end(&sd));
		device_update(a[1], g_variant_builder_end(&b));
	} else if (!strcmp(cmd, "md") && n >= 5) {
		GVariantBuilder md, b;
		g_variant_builder_init(&md, G_VARIANT_TYPE("a{qv}"));
		g_variant_builder_add(&md, "{qv}", (guint16)strtoul(a[3], NULL, 0), bytes_from_hex(a[4]));
		g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
		g_variant_builder_add(&b, "{sv}", "RSSI", g_variant_new_int16(atoi(a[2])));
		g_variant_builder_add(&b, "{sv}", "ManufacturerData", g_variant_builder_end(&md));
		device_update(a[1], g_variant_builder_end(&b));
	} else if (!strcmp(cmd, "uuids") && n >= 3) {
		char **u = g_strsplit(a[2], ",", -1);
		GVariantBuilder b;
		g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
		g_variant_builder_add(&b, "{sv}", "UUIDs", g_variant_new_strv((const char *const *)u, -1));
		device_update(a[1], g_variant_builder_end(&b));
		g_strfreev(u);
	} else if (!strcmp(cmd, "bond") && n >= 2) {
		GVariantBuilder b;
		g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
		g_variant_builder_add(&b, "{sv}", "Paired", g_variant_new_boolean(TRUE));
		g_variant_builder_add(&b, "{sv}", "Bonded", g_variant_new_boolean(TRUE));
		device_update(a[1], g_variant_builder_end(&b));
	} else if (!strcmp(cmd, "gone") && n >= 2) {
		char *path = dev_path(a[1]);
		g_hash_table_remove(devices, path);
		const char *names[] = {"org.bluez.Device1", NULL};
		g_dbus_connection_emit_signal(bus, NULL, "/", "org.freedesktop.DBus.ObjectManager", "InterfacesRemoved",
		                              g_variant_new("(o^as)", path, names), NULL);
		g_dbus_connection_flush_sync(bus, NULL, NULL);
		g_free(path);
	} else if (!strcmp(cmd, "powered") && n >= 2) {
		powered = atoi(a[1]) != 0;
		if (!powered)
			discovering = FALSE;
		emit_one(ADAPTER, "org.bluez.Adapter1", "Powered", g_variant_new_boolean(powered));
	} else if (!strcmp(cmd, "remove_adapter")) {
		adapter_present = FALSE;
		discovering = FALSE;
		g_dbus_connection_unregister_object(bus, adapter_reg);
		g_hash_table_remove_all(devices);
		const char *names[] = {"org.freedesktop.DBus.Properties", "org.bluez.Adapter1", NULL};
		g_dbus_connection_emit_signal(bus, NULL, "/", "org.freedesktop.DBus.ObjectManager", "InterfacesRemoved",
		                              g_variant_new("(o^as)", ADAPTER, names), NULL);
		g_dbus_connection_flush_sync(bus, NULL, NULL);
	} else if (!strcmp(cmd, "add_adapter")) {
		adapter_present = TRUE;
		register_adapter();
		GVariantBuilder ifaces;
		g_variant_builder_init(&ifaces, G_VARIANT_TYPE("a{sa{sv}}"));
		g_variant_builder_add(&ifaces, "{s@a{sv}}", "org.bluez.Adapter1", adapter_props());
		g_dbus_connection_emit_signal(bus, NULL, "/", "org.freedesktop.DBus.ObjectManager", "InterfacesAdded",
		                              g_variant_new("(oa{sa{sv}})", ADAPTER, &ifaces), NULL);
		g_dbus_connection_flush_sync(bus, NULL, NULL);
	} else if (!strcmp(cmd, "quit_bluez")) {
		if (owner)
			g_bus_unown_name(owner);
		owner = 0;
		discovering = FALSE;
		g_dbus_connection_flush_sync(bus, NULL, NULL);
	} else if (!strcmp(cmd, "own_bluez")) {
		owner = g_bus_own_name_on_connection(bus, "org.bluez", G_BUS_NAME_OWNER_FLAGS_NONE, NULL, NULL, NULL, NULL);
		g_dbus_connection_flush_sync(bus, NULL, NULL);
	}
	g_strfreev(a);
	say("ok %s", line);
}

static gboolean on_control(GIOChannel *ch, GIOCondition cond, gpointer data)
{
	char *line = NULL;
	gsize len;
	while (g_io_channel_read_line(ch, &line, &len, NULL, NULL) == G_IO_STATUS_NORMAL) {
		g_strchomp(line);
		if (*line)
			command(line);
		g_free(line);
	}
	return G_SOURCE_CONTINUE;
}

int main(int argc, char **argv)
{
	if (argc < 2) {
		fprintf(stderr, "usage: mock-bluez CONTROL_FIFO\n");
		return 2;
	}
	GError *err = NULL;
	bus = g_bus_get_sync(G_BUS_TYPE_SYSTEM, NULL, &err);
	if (!bus) {
		fprintf(stderr, "mock-bluez: %s\n", err->message);
		return 1;
	}
	devices = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, (GDestroyNotify)g_variant_unref);
	info = g_dbus_node_info_new_for_xml(xml, &err);
	if (!g_dbus_connection_register_object(bus, "/", g_dbus_node_info_lookup_interface(info, "org.freedesktop.DBus.ObjectManager"),
	                                       &vtable, NULL, NULL, &err)) {
		fprintf(stderr, "mock-bluez: %s\n", err->message);
		return 1;
	}
	register_adapter();
	owner = g_bus_own_name_on_connection(bus, "org.bluez", G_BUS_NAME_OWNER_FLAGS_NONE, NULL, NULL, NULL, NULL);
	int fd = open(argv[1], O_RDWR | O_NONBLOCK);
	if (fd < 0) {
		perror(argv[1]);
		return 1;
	}
	GIOChannel *ch = g_io_channel_unix_new(fd);
	g_io_channel_set_flags(ch, G_IO_FLAG_NONBLOCK, NULL);
	g_io_add_watch(ch, G_IO_IN, on_control, NULL);
	say("ready");
	g_main_loop_run(g_main_loop_new(NULL, FALSE));
	return 0;
}
