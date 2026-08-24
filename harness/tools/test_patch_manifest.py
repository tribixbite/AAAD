import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID = "http://schemas.android.com/apk/res/android"
A = f"{{{ANDROID}}}"
SCRIPT = Path(__file__).with_name("patch_manifest.py")


def manifest(service_filter: str = "") -> str:
    service = ""
    if service_filter:
        kind, value = service_filter.split(":", 1)
        tag = "category" if kind == "category" else "action"
        service = f"""
        <service android:name="com.example.RealCarService" android:exported="true">
          <intent-filter><{tag} android:name="{value}" /></intent-filter>
        </service>"""
    return f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="{ANDROID}" package="com.example.phone">
  <application android:label="Phone app">
    <activity android:name="com.example.phone.MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
    </activity>
    {service}
  </application>
</manifest>"""


class PatchManifestTest(unittest.TestCase):
    def run_patch(self, service_filter: str = "", discovery: str = "template"):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text(manifest(service_filter), encoding="utf-8")
            result = subprocess.run(
                [
                    "python3",
                    str(SCRIPT),
                    str(path),
                    "com.example.phone",
                    "com.example.phone.aaad",
                    " (Car)",
                    discovery,
                ],
                check=True,
                capture_output=True,
                text=True,
            )
            return ET.parse(path).getroot(), result.stdout

    def test_injects_bridge_for_plain_phone_app(self):
        root, output = self.run_patch(discovery="projection")
        app = root.find("application")
        services = app.findall("service")
        self.assertEqual(services, [])
        permissions = {p.get(f"{A}name") for p in root.findall("uses-permission")}
        self.assertIn("androidx.car.app.ACCESS_SURFACE", permissions)
        self.assertIn("androidx.car.app.MAP_TEMPLATES", permissions)
        self.assertIn("androidx.car.app.NAVIGATION_TEMPLATES", permissions)
        metadata = {
            item.get(f"{A}name"): item.get(f"{A}value")
            for item in app.findall("meta-data")
        }
        self.assertEqual(app.get(f"{A}appCategory"), "game")
        self.assertEqual(metadata["androidx.car.app.minCarApiLevel"], "7")
        component_names = {
            item.get(f"{A}name")
            for item in app.findall("activity") + app.findall("receiver")
        }
        self.assertIn("androidx.car.app.CarAppPermissionActivity", component_names)
        self.assertIn(
            "androidx.car.app.notification.CarAppNotificationBroadcastReceiver",
            component_names,
        )
        providers = root.find("queries").findall("provider")
        self.assertEqual(
            providers[0].get(f"{A}authorities"),
            "androidx.car.app.connection",
        )
        self.assertIn("CAR_USES=projection", output)
        self.assertIn("CAR_NEEDS_BRIDGE=yes", output)
        # DEX classes do not move when only the application id changes.
        self.assertEqual(
            app.find("activity").get(f"{A}name"),
            "com.example.phone.MainActivity",
        )

        launcher_filter = app.find("activity").find("intent-filter")
        launcher_categories = {
            category.get(f"{A}name")
            for category in launcher_filter.findall("category")
        }
        self.assertTrue(
            {
                "android.intent.category.DEFAULT",
                "android.intent.category.CAR_LAUNCHER",
                "androidx.car.app.category.NAVIGATION",
                "android.intent.category.APP_MAPS",
            }.issubset(launcher_categories)
        )

    def test_template_game_control_declares_bridge_service(self):
        root, output = self.run_patch()
        app = root.find("application")
        self.assertEqual(app.get(f"{A}appCategory"), "game")
        services = app.findall("service")
        self.assertEqual(len(services), 1)
        self.assertEqual(
            services[0].get(f"{A}name"),
            "com.legs.appsforaa.carify.CarifyCarAppService",
        )
        intent_filter = services[0].find("intent-filter")
        self.assertEqual(
            intent_filter.find("action").get(f"{A}name"),
            "androidx.car.app.CarAppService",
        )
        self.assertEqual(
            intent_filter.find("category").get(f"{A}name"),
            "androidx.car.app.category.NAVIGATION",
        )
        self.assertIn("CAR_USES=template", output)
        self.assertIn("CAR_NEEDS_BRIDGE=yes", output)

    def test_preserves_existing_projection_service(self):
        root, output = self.run_patch(
            "category:com.google.android.gms.car.category.CATEGORY_PROJECTION"
        )
        self.assertEqual(len(root.find("application").findall("service")), 1)
        self.assertIn("CAR_USES=projection", output)
        self.assertIn("CAR_NEEDS_BRIDGE=no", output)

    def test_preserves_existing_template_service(self):
        root, output = self.run_patch("action:androidx.car.app.CarAppService")
        self.assertEqual(len(root.find("application").findall("service")), 1)
        self.assertIn("CAR_USES=template", output)
        self.assertIn("CAR_NEEDS_BRIDGE=no", output)


if __name__ == "__main__":
    unittest.main()
