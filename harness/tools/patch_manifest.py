#!/usr/bin/env python3
"""
Rewrites a decoded AndroidManifest.xml into a side-by-side, Android-Auto-visible clone.

The hard constraint: **the app's class names must not move.** The original DEX is not rewritten,
so every existing `android:name` still refers to the
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


def remove_meta(parent, name):
    """Removes metadata that would make Gearhead classify this as a driving app."""
    for existing in parent.findall("meta-data"):
        if existing.get(f"{A}name") == name:
            parent.remove(existing)


# 5. Keep only Android Auto's parked-game Activity route.
#
#    A never-before-installed S25U control proved that installer-of-record spoofing does not make
#    a shell-initiated Car App Library package trusted. Android Auto rejects maps/template clones
#    before discovery. Parked games are covered by Unknown sources and are the only supported
#    general Activity route on Android Auto, so arbitrary sideloaded copies use that honest route.
#
#    A game/Navigation hybrid is still a Car App Library app, so Unknown sources does not apply.
#    Remove only the car discovery declarations from publisher services; unrelated phone actions
#    and the services themselves remain intact.
for service in app.findall("service"):
    for intent_filter in list(service.findall("intent-filter")):
        for action in list(intent_filter.findall("action")):
            if action.get(f"{A}name") == CAR_APP_SERVICE_ACTION:
                intent_filter.remove(action)
        for category in list(intent_filter.findall("category")):
            name = category.get(f"{A}name", "")
            if name == PROJECTION_CATEGORY or name.startswith("androidx.car.app.category."):
                intent_filter.remove(category)
        if not intent_filter.findall("action"):
            service.remove(intent_filter)

# A shell install can change the installer label but not its initiating package. Android Auto
# admits the sideload through its parked-app Unknown sources path, which requires game.
app.set(f"{A}appCategory", "game")

remove_meta(app, "com.google.android.gms.car.application")
remove_meta(app, "androidx.car.app.minCarApiLevel")
remove_meta(app, "distractionOptimized")
changes.append("uses the parked-game CAR_LAUNCHER Activity route")
print("CAR_USES=parked")
print("CAR_BACKED=yes")
print("CAR_NEEDS_BRIDGE=no")

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
    remove_meta(activity, "distractionOptimized")
    # An orientation lock is the single most common reason a phone Activity renders as a
    # letterboxed sliver on a landscape head unit.
    if activity.get(f"{A}screenOrientation") is not None:
        del activity.attrib[f"{A}screenOrientation"]
        changes.append("removed screenOrientation lock from launcher")
    activity.set(f"{A}resizeableActivity", "true")
    for category in list(intent_filter.findall("category")):
        if category.get(f"{A}name") in {
                "androidx.car.app.category.NAVIGATION",
                "android.intent.category.APP_MAPS"}:
            intent_filter.remove(category)
    car_categories = (
        "android.intent.category.DEFAULT",
        "android.intent.category.CAR_LAUNCHER",
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
