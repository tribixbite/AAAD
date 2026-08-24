#!/usr/bin/env python3
"""
Rewrites a decoded AndroidManifest.xml into a side-by-side, Android-Auto-visible clone.

The hard constraint: **the app's class names must not move.** Carify can add its own bridge DEX,
but the original DEX is not rewritten, so every existing `android:name` still refers to the
original package's classes. Renaming those would produce an app whose manifest points at classes
that do not exist. Only *identifiers* get
the new package: the manifest package itself, permissions the app declares, provider authorities,
and task affinity. Those are the ones that collide with the original install if left alone -
duplicate permissions and duplicate authorities are both hard install failures.
"""
import sys
import xml.etree.ElementTree as ET

ANDROID = "http://schemas.android.com/apk/res/android"
A = f"{{{ANDROID}}}"
ET.register_namespace("android", ANDROID)

path, old_pkg, new_pkg, label_suffix = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
discovery = sys.argv[5] if len(sys.argv) > 5 else "template"
if discovery not in {"projection", "template"}:
    sys.exit(f"unsupported Carify discovery mode: {discovery}")

PROJECTION_CATEGORY = "com.google.android.gms.car.category.CATEGORY_PROJECTION"
CAR_APP_SERVICE_ACTION = "androidx.car.app.CarAppService"
BRIDGE_SERVICE = "com.legs.appsforaa.carify.CarifyCarAppService"

tree = ET.parse(path)
root = tree.getroot()

changes = []


def rename(value: str) -> str:
    """Repoint an identifier at the new package, preserving whatever follows it."""
    if value == old_pkg:
        return new_pkg
    if value.startswith(old_pkg + "."):
        return new_pkg + value[len(old_pkg):]
    return value


# 1. The manifest package. aapt2 rebuilds the resource table's package name from this.
root.set("package", new_pkg)
changes.append(f"package -> {new_pkg}")

# 2. A shared user id ties the app to the original's sandbox and requires the same signature;
#    a re-signed clone can never satisfy it, so the install would simply be refused.
for attr in (f"{A}sharedUserId", f"{A}sharedUserLabel"):
    if root.get(attr) is not None:
        del root.attrib[attr]
        changes.append(f"dropped {attr.split('}')[-1]}")

app = root.find("application")
if app is None:
    sys.exit("no <application> element")

# 3. Identifier attributes, everywhere they appear. Deliberately NOT android:name on components.
IDENTIFIER_ATTRS = (f"{A}authorities", f"{A}taskAffinity", f"{A}permission",
                    f"{A}targetPackage", f"{A}process")
for element in root.iter():
    for attr in IDENTIFIER_ATTRS:
        value = element.get(attr)
        if value:
            # authorities may be a semicolon-separated list
            renamed = ";".join(rename(v) for v in value.split(";"))
            if renamed != value:
                element.set(attr, renamed)
                changes.append(f"{attr.split('}')[-1]}: {value} -> {renamed}")

# 4. Permissions the app declares itself, plus its own uses-permission entries pointing at them.
for element in list(root.findall("permission")) + list(root.findall("uses-permission")):
    name = element.get(f"{A}name", "")
    renamed = rename(name)
    if renamed != name:
        element.set(f"{A}name", renamed)
        changes.append(f"permission: {name} -> {renamed}")


def set_meta(parent, name, *, value=None, resource=None):
    """Adds or replaces a <meta-data>, so re-running is idempotent."""
    for existing in parent.findall("meta-data"):
        if existing.get(f"{A}name") == name:
            parent.remove(existing)
    meta = ET.SubElement(parent, "meta-data")
    meta.set(f"{A}name", name)
    if value is not None:
        meta.set(f"{A}value", value)
    if resource is not None:
        meta.set(f"{A}resource", resource)


# 5. What car surface can this app actually back?
#
#    The S25U control established the complete working signature: the AndroidX runtime and service,
#    full launcher categories, and an application category. The first isolated control used
#    appCategory=game and was FOUND, but Android Auto restricts games to parked use. The bridge is
#    a navigation template, so its driving-capable application category is maps.
#
#    Existing projection/template implementations stay authoritative. The bridge is added only
#    when the APK has neither, avoiding duplicate Car App runtimes in apps such as AABrowser.
has_projection_service = False
has_car_app_service = False
for service in app.findall("service"):
    for intent_filter in service.findall("intent-filter"):
        cats = {c.get(f"{A}name") for c in intent_filter.findall("category")}
        acts = {a.get(f"{A}name") for a in intent_filter.findall("action")}
        if PROJECTION_CATEGORY in cats:
            has_projection_service = True
        if CAR_APP_SERVICE_ACTION in acts:
            has_car_app_service = True

needs_bridge = not has_projection_service and not has_car_app_service
if has_car_app_service and not has_projection_service:
    car_uses, backing = "template", "the app's own androidx CarAppService"
elif has_projection_service:
    car_uses, backing = "projection", "the app's own CATEGORY_PROJECTION service"
else:
    car_uses = discovery
    backing = f"the injected Carify {discovery} runtime and launcher Activity"


def add_uses_permission(name: str):
    if any(p.get(f"{A}name") == name for p in root.findall("uses-permission")):
        return
    permission = ET.Element("uses-permission")
    permission.set(f"{A}name", name)
    # Keep conventional manifest ordering even though APKEditor's parser accepts either order.
    root.insert(list(root).index(app), permission)
    changes.append(f"added permission {name}")


if needs_bridge:
    add_uses_permission("androidx.car.app.ACCESS_SURFACE")
    add_uses_permission("androidx.car.app.MAP_TEMPLATES")
    add_uses_permission("androidx.car.app.NAVIGATION_TEMPLATES")

    queries = root.find("queries")
    if queries is None:
        queries = ET.Element("queries")
        root.insert(list(root).index(app), queries)
    if not any(
            provider.get(f"{A}authorities") == "androidx.car.app.connection"
            for provider in queries.findall("provider")):
        provider = ET.SubElement(queries, "provider")
        provider.set(f"{A}name", "androidx.car.app.connection.provider")
        provider.set(f"{A}authorities", "androidx.car.app.connection")

    permission_activity = ET.SubElement(app, "activity")
    permission_activity.set(f"{A}name", "androidx.car.app.CarAppPermissionActivity")
    permission_activity.set(f"{A}exported", "false")
    permission_activity.set(f"{A}theme", "@android:style/Theme.Translucent.NoTitleBar")

    notification_receiver = ET.SubElement(app, "receiver")
    notification_receiver.set(
        f"{A}name", "androidx.car.app.notification.CarAppNotificationBroadcastReceiver")
    notification_receiver.set(f"{A}exported", "false")

    if discovery == "template":
        bridge_service = ET.SubElement(app, "service")
        bridge_service.set(f"{A}name", BRIDGE_SERVICE)
        bridge_service.set(f"{A}exported", "true")
        bridge_filter = ET.SubElement(bridge_service, "intent-filter")
        bridge_action = ET.SubElement(bridge_filter, "action")
        bridge_action.set(f"{A}name", CAR_APP_SERVICE_ACTION)
        bridge_category = ET.SubElement(bridge_filter, "category")
        bridge_category.set(f"{A}name", "androidx.car.app.category.NAVIGATION")

    set_meta(app, "androidx.car.app.minCarApiLevel", value="7")
    changes.append(f"injected AndroidX {discovery} runtime components")

# CATEGORY_GAME is parked-only. CATEGORY_MAPS matches the navigation declarations added below and
# remains eligible while driving. Apply it to repaired publisher car apps too: preserving a
# publisher's stale game category would preserve the exact restriction Carify is meant to remove.
app.set(f"{A}appCategory", "maps")

set_meta(app, "com.google.android.gms.car.application", resource="@xml/automotive_app_desc")
set_meta(app, "distractionOptimized", value="true")
changes.append(f"car descriptor declares <uses name=\"{car_uses}\"/>")
changes.append(f"  backed by {backing}")
print(f"CAR_USES={car_uses}")
print("CAR_BACKED=yes")
print(f"CAR_NEEDS_BRIDGE={'yes' if needs_bridge else 'no'}")

# 6. Make the UI as adaptable as the manifest can: a phone Activity on a head unit is a fixed
#    portrait box unless it is told otherwise. This cannot fix a layout that hardcodes phone
#    dimensions, but it removes the manifest-level blockers.
app.set(f"{A}resizeableActivity", "true")

launchers = []
for activity in app.findall("activity") + app.findall("activity-alias"):
    for intent_filter in activity.findall("intent-filter"):
        actions = {a.get(f"{A}name") for a in intent_filter.findall("action")}
        categories = {c.get(f"{A}name") for c in intent_filter.findall("category")}
        if "android.intent.action.MAIN" in actions and \
                "android.intent.category.LAUNCHER" in categories:
            launchers.append((activity, intent_filter))

for activity, intent_filter in launchers:
    set_meta(activity, "distractionOptimized", value="true")
    # An orientation lock is the single most common reason a phone Activity renders as a
    # letterboxed sliver on a landscape head unit.
    if activity.get(f"{A}screenOrientation") is not None:
        del activity.attrib[f"{A}screenOrientation"]
        changes.append("removed screenOrientation lock from launcher")
    activity.set(f"{A}resizeableActivity", "true")
    # Match the resolving Activity shape of listed projected apps such as AABrowser. Gearhead
    # filters the whole intent, not merely CAR_LAUNCHER.
    car_categories = (
        "android.intent.category.DEFAULT",
        "android.intent.category.CAR_LAUNCHER",
        "androidx.car.app.category.NAVIGATION",
        "android.intent.category.APP_MAPS",
    )
    existing_categories = {
        c.get(f"{A}name") for c in intent_filter.findall("category")
    }
    for car_category in car_categories:
        if car_category in existing_categories:
            continue
        category = ET.SubElement(intent_filter, "category")
        category.set(f"{A}name", car_category)
        changes.append(f"added {car_category} to launcher intent-filter")

changes.append(f"launcher activities patched: {len(launchers)}")

# 7. A visibly different label, so the clone is distinguishable from the original on the phone.
label = app.get(f"{A}label")
if label and not label.startswith("@"):
    app.set(f"{A}label", f"{label}{label_suffix}")
    changes.append(f"label -> {label}{label_suffix}")
else:
    # A resource-backed label cannot be edited here; the caller overrides it in res/values.
    changes.append(f"label is a resource ({label}); caller must override it")

# 8. The @null meta-data trap: Android 14+ rejects a <meta-data> with neither value nor resource.
for meta in root.iter("meta-data"):
    if meta.get(f"{A}resource") == "@null" and meta.get(f"{A}value") is None:
        meta.set(f"{A}resource", "@mipmap/ic_launcher")
        changes.append(f"replaced @null resource on {meta.get(f'{A}name')}")

tree.write(path, encoding="utf-8", xml_declaration=True)
print("\n".join(f"  {c}" for c in changes))
print(f"  LABEL_IS_RESOURCE={label}" if label and label.startswith("@") else "")
