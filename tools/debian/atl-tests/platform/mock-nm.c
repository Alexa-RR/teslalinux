/* mock-nm.c: a NetworkManager and ModemManager for PlatformTest's network phase, on a private
 * system bus (DBUS_SYSTEM_BUS_ADDRESS). It answers the calls ATL's ConnectivityManager makes
 * (NetworkManager GetDeviceByIpIface and the properties of the manager, a device and its
 * Wireless/Wired interface; ModemManager GetManagedObjects) for one interface, and changes its state
 * on commands read from the control FIFO (argv[1]), one per line, emitting PropertiesChanged as the
 * daemons do:
 *   nm_type N | nm_metered N | nm_driver S | nm_speed MBPS | wifi_bitrate KBPS | wifi_strength PCT
 *   modem_port IFACE | mm_access BITS | mm_registration N | mm_quit
 * After each command it prints "ok <command>" on stdout.
 * Build: gcc -o mock-nm mock-nm.c $(pkg-config --cflags --libs gio-2.0)
 * Never part of the runtime. */
#include <fcntl.h>
#include <gio/gio.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define IFACE_NAME "tlwwan0"
#define NM_PATH "/org/freedesktop/NetworkManager"
#define DEV_PATH "/org/freedesktop/NetworkManager/Devices/7"
#define AP_PATH "/org/freedesktop/NetworkManager/AccessPoint/3"
#define MM_PATH "/org/freedesktop/ModemManager1"
#define MODEM_PATH "/org/freedesktop/ModemManager1/Modem/0"

static GDBusConnection *bus;
static guint mm_owner;
static guint32 nm_type = 1, nm_metered = 4, nm_speed = 0, wifi_bitrate = 0, wifi_strength = 0;
static char *nm_driver;
static char *modem_port;
static guint32 mm_access = 0, mm_registration = 1;

static const char *xml =
	"<node>"
	"<interface name='org.freedesktop.NetworkManager'>"
	"<method name='GetDeviceByIpIface'><arg type='s' direction='in'/><arg type='o' direction='out'/></method>"
	"<property name='Metered' type='u' access='read'/><property name='Connectivity' type='u' access='read'/>"
	"<property name='State' type='u' access='read'/><property name='Version' type='s' access='read'/>"
	"<property name='PrimaryConnection' type='o' access='read'/><property name='NetworkingEnabled' type='b' access='read'/>"
	"</interface>"
	"<interface name='org.freedesktop.NetworkManager.Device'>"
	"<property name='DeviceType' type='u' access='read'/><property name='Metered' type='u' access='read'/>"
	"<property name='State' type='u' access='read'/><property name='Driver' type='s' access='read'/>"
	"<property name='Udi' type='s' access='read'/><property name='Interface' type='s' access='read'/>"
	"</interface>"
	"<interface name='org.freedesktop.NetworkManager.Device.Wired'><property name='Speed' type='u' access='read'/></interface>"
	"<interface name='org.freedesktop.NetworkManager.Device.Wireless'>"
	"<property name='Bitrate' type='u' access='read'/><property name='ActiveAccessPoint' type='o' access='read'/>"
	"</interface>"
	"<interface name='org.freedesktop.NetworkManager.AccessPoint'><property name='Strength' type='y' access='read'/></interface>"
	"<interface name='org.freedesktop.DBus.ObjectManager'>"
	"<method name='GetManagedObjects'><arg type='a{oa{sa{sv}}}' direction='out'/></method>"
	"</interface>"
	"</node>";
static GDBusNodeInfo *info;

static GVariant *get_property(GDBusConnection *c, const char *sender, const char *path, const char *iface,
                              const char *prop, GError **error, gpointer data)
{
	if (!strcmp(iface, "org.freedesktop.NetworkManager")) {
		if (!strcmp(prop, "Metered"))
			return g_variant_new_uint32(nm_metered);
		if (!strcmp(prop, "Connectivity"))
			return g_variant_new_uint32(4); /* NM_CONNECTIVITY_FULL */
		if (!strcmp(prop, "State"))
			return g_variant_new_uint32(70); /* NM_STATE_CONNECTED_GLOBAL */
		if (!strcmp(prop, "Version"))
			return g_variant_new_string("1.52.1");
		if (!strcmp(prop, "PrimaryConnection"))
			return g_variant_new_object_path("/org/freedesktop/NetworkManager/ActiveConnection/1");
		if (!strcmp(prop, "NetworkingEnabled"))
			return g_variant_new_boolean(TRUE);
	} else if (!strcmp(iface, "org.freedesktop.NetworkManager.Device")) {
		if (!strcmp(prop, "DeviceType"))
			return g_variant_new_uint32(nm_type);
		if (!strcmp(prop, "Metered"))
			return g_variant_new_uint32(nm_metered);
		if (!strcmp(prop, "State"))
			return g_variant_new_uint32(100); /* NM_DEVICE_STATE_ACTIVATED */
		if (!strcmp(prop, "Driver"))
			return g_variant_new_string(nm_driver);
		if (!strcmp(prop, "Udi"))
			return g_variant_new_string("/sys/devices/virtual/net/" IFACE_NAME);
		if (!strcmp(prop, "Interface"))
			return g_variant_new_string(IFACE_NAME);
	} else if (!strcmp(iface, "org.freedesktop.NetworkManager.Device.Wired") && !strcmp(prop, "Speed")) {
		return g_variant_new_uint32(nm_speed);
	} else if (!strcmp(iface, "org.freedesktop.NetworkManager.Device.Wireless")) {
		if (!strcmp(prop, "Bitrate"))
			return g_variant_new_uint32(wifi_bitrate);
		if (!strcmp(prop, "ActiveAccessPoint"))
			return g_variant_new_object_path(AP_PATH);
	} else if (!strcmp(iface, "org.freedesktop.NetworkManager.AccessPoint") && !strcmp(prop, "Strength")) {
		return g_variant_new_byte((guchar)wifi_strength);
	}
	g_set_error(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_PROPERTY, "no property %s.%s", iface, prop);
	return NULL;
}

static GVariant *managed_objects(void)
{
	GVariantBuilder objects, ifaces, modem, ports, gpp;
	g_variant_builder_init(&objects, G_VARIANT_TYPE("a{oa{sa{sv}}}"));
	g_variant_builder_init(&ifaces, G_VARIANT_TYPE("a{sa{sv}}"));
	g_variant_builder_init(&modem, G_VARIANT_TYPE("a{sv}"));
	g_variant_builder_init(&ports, G_VARIANT_TYPE("a(su)"));
	g_variant_builder_add(&ports, "(su)", modem_port, 2); /* MM_MODEM_PORT_TYPE_NET */
	g_variant_builder_add(&ports, "(su)", "cdc-wdm0", 6); /* MM_MODEM_PORT_TYPE_QMI */
	g_variant_builder_add(&modem, "{sv}", "Ports", g_variant_builder_end(&ports));
	g_variant_builder_add(&modem, "{sv}", "AccessTechnologies", g_variant_new_uint32(mm_access));
	g_variant_builder_add(&modem, "{sv}", "State", g_variant_new_int32(11)); /* MM_MODEM_STATE_CONNECTED */
	g_variant_builder_add(&modem, "{sv}", "SignalQuality", g_variant_new("(ub)", 75, TRUE));
	g_variant_builder_add(&ifaces, "{sa{sv}}", "org.freedesktop.ModemManager1.Modem", &modem);
	g_variant_builder_init(&gpp, G_VARIANT_TYPE("a{sv}"));
	g_variant_builder_add(&gpp, "{sv}", "RegistrationState", g_variant_new_uint32(mm_registration));
	g_variant_builder_add(&gpp, "{sv}", "OperatorName", g_variant_new_string("Test Mobile"));
	g_variant_builder_add(&ifaces, "{sa{sv}}", "org.freedesktop.ModemManager1.Modem.Modem3gpp", &gpp);
	g_variant_builder_add(&objects, "{oa{sa{sv}}}", MODEM_PATH, &ifaces);
	return g_variant_new("(a{oa{sa{sv}}})", &objects);
}

static void method_call(GDBusConnection *c, const char *sender, const char *path, const char *iface, const char *method,
                        GVariant *params, GDBusMethodInvocation *inv, gpointer data)
{
	if (!strcmp(method, "GetDeviceByIpIface")) {
		const char *name;
		g_variant_get(params, "(&s)", &name);
		if (!strcmp(name, IFACE_NAME))
			g_dbus_method_invocation_return_value(inv, g_variant_new("(o)", DEV_PATH));
		else
			g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.NetworkManager.UnknownDevice", "No device found for the requested iface.");
	} else if (!strcmp(method, "GetManagedObjects")) {
		g_dbus_method_invocation_return_value(inv, managed_objects());
	} else {
		g_dbus_method_invocation_return_dbus_error(inv, "org.freedesktop.DBus.Error.UnknownMethod", method);
	}
}

static const GDBusInterfaceVTable vtable = {method_call, get_property, NULL};

static void reg(const char *path, const char *iface)
{
	GError *err = NULL;
	if (!g_dbus_connection_register_object(bus, path, g_dbus_node_info_lookup_interface(info, iface), &vtable, NULL, NULL, &err)) {
		fprintf(stderr, "mock-nm: register %s %s: %s\n", path, iface, err->message);
		exit(1);
	}
}

static void changed(const char *path, const char *iface, const char *prop, GVariant *value)
{
	GVariantBuilder b;
	g_variant_builder_init(&b, G_VARIANT_TYPE("a{sv}"));
	g_variant_builder_add(&b, "{sv}", prop, value);
	g_dbus_connection_emit_signal(bus, NULL, path, "org.freedesktop.DBus.Properties", "PropertiesChanged",
	                              g_variant_new("(sa{sv}as)", iface, &b, NULL), NULL);
	g_dbus_connection_flush_sync(bus, NULL, NULL);
}

static void command(char *line)
{
	char *arg = strchr(line, ' ');
	if (arg)
		*arg++ = 0;
	const char *dev = "org.freedesktop.NetworkManager.Device", *modem = "org.freedesktop.ModemManager1.Modem";
	if (!strcmp(line, "nm_type")) {
		nm_type = atoi(arg);
		changed(DEV_PATH, dev, "DeviceType", g_variant_new_uint32(nm_type));
	} else if (!strcmp(line, "nm_metered")) {
		nm_metered = atoi(arg);
		changed(DEV_PATH, dev, "Metered", g_variant_new_uint32(nm_metered));
		changed(NM_PATH, "org.freedesktop.NetworkManager", "Metered", g_variant_new_uint32(nm_metered));
	} else if (!strcmp(line, "nm_driver")) {
		g_free(nm_driver);
		nm_driver = g_strdup(arg);
		changed(DEV_PATH, dev, "Driver", g_variant_new_string(nm_driver));
	} else if (!strcmp(line, "nm_speed")) {
		nm_speed = atoi(arg);
		changed(DEV_PATH, "org.freedesktop.NetworkManager.Device.Wired", "Speed", g_variant_new_uint32(nm_speed));
	} else if (!strcmp(line, "wifi_bitrate")) {
		wifi_bitrate = atoi(arg);
		changed(DEV_PATH, "org.freedesktop.NetworkManager.Device.Wireless", "Bitrate", g_variant_new_uint32(wifi_bitrate));
	} else if (!strcmp(line, "wifi_strength")) {
		wifi_strength = atoi(arg);
		changed(AP_PATH, "org.freedesktop.NetworkManager.AccessPoint", "Strength", g_variant_new_byte((guchar)wifi_strength));
	} else if (!strcmp(line, "modem_port")) {
		g_free(modem_port);
		modem_port = g_strdup(arg);
		changed(MODEM_PATH, modem, "Ports", g_variant_new_parsed("[(%s, @u 2)]", modem_port));
	} else if (!strcmp(line, "mm_access")) {
		mm_access = strtoul(arg, NULL, 0);
		changed(MODEM_PATH, modem, "AccessTechnologies", g_variant_new_uint32(mm_access));
	} else if (!strcmp(line, "mm_registration")) {
		mm_registration = atoi(arg);
		changed(MODEM_PATH, "org.freedesktop.ModemManager1.Modem.Modem3gpp", "RegistrationState", g_variant_new_uint32(mm_registration));
	} else if (!strcmp(line, "mm_quit")) {
		if (mm_owner)
			g_bus_unown_name(mm_owner);
		mm_owner = 0;
		g_dbus_connection_flush_sync(bus, NULL, NULL);
	}
	printf("ok %s\n", line);
	fflush(stdout);
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
		fprintf(stderr, "usage: mock-nm CONTROL_FIFO\n");
		return 2;
	}
	nm_driver = g_strdup("cdc_ether");
	modem_port = g_strdup("wwan9");
	GError *err = NULL;
	bus = g_bus_get_sync(G_BUS_TYPE_SYSTEM, NULL, &err);
	if (!bus) {
		fprintf(stderr, "mock-nm: %s\n", err->message);
		return 1;
	}
	info = g_dbus_node_info_new_for_xml(xml, &err);
	reg(NM_PATH, "org.freedesktop.NetworkManager");
	reg(DEV_PATH, "org.freedesktop.NetworkManager.Device");
	reg(DEV_PATH, "org.freedesktop.NetworkManager.Device.Wired");
	reg(DEV_PATH, "org.freedesktop.NetworkManager.Device.Wireless");
	reg(AP_PATH, "org.freedesktop.NetworkManager.AccessPoint");
	reg(MM_PATH, "org.freedesktop.DBus.ObjectManager");
	g_bus_own_name_on_connection(bus, "org.freedesktop.NetworkManager", G_BUS_NAME_OWNER_FLAGS_NONE, NULL, NULL, NULL, NULL);
	mm_owner = g_bus_own_name_on_connection(bus, "org.freedesktop.ModemManager1", G_BUS_NAME_OWNER_FLAGS_NONE, NULL, NULL, NULL, NULL);
	int fd = open(argv[1], O_RDWR | O_NONBLOCK);
	if (fd < 0) {
		perror(argv[1]);
		return 1;
	}
	GIOChannel *ch = g_io_channel_unix_new(fd);
	g_io_channel_set_flags(ch, G_IO_FLAG_NONBLOCK, NULL);
	g_io_add_watch(ch, G_IO_IN, on_control, NULL);
	printf("ready\n");
	fflush(stdout);
	g_main_loop_run(g_main_loop_new(NULL, FALSE));
	return 0;
}
