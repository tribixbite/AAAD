import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID = "http://schemas.android.com/apk/res/android"
A = f"{{{ANDROID}}}"
SCRIPT = Path(__file__).with_name("patch_manifest.py")


def manifest(service_filter: str = "", app_category: str = "") -> str:
    service = ""
    if service_filter:
        kind, value = service_filter.split(":", 1)
        tag = "category" if kind == "category" else "action"
        service = f"""
        <service android:name="com.example.RealCarService" android:exported="true">
          <intent-filter><{tag} android:name="{value}" /></intent-filter>
        </service>"""
    category = f' android:appCategory="{app_category}"' if app_category else ""
    return f"""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="{ANDROID}" package="com.example.phone">
  <application android:label="Phone app"{category}>
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
    def run_patch(
            self,
            service_filter: str = "",
            discovery: str = "template",
            app_category: str = ""):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "AndroidManifest.xml"
            path.write_text(manifest(service_filter, app_category), encoding="utf-8")
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

    def test_plain_phone_app_uses_only_parked_activity_route(self):
        root, output = self.run_patch(discovery="projection")
        app = root.find("application")
        services = app.findall("service")
        self.assertEqual(services, [])
        permissions = {p.get(f"{A}name") for p in root.findall("uses-permission")}
        self.assertNotIn("androidx.car.app.ACCESS_SURFACE", permissions)
        self.assertEqual(app.get(f"{A}appCategory"), "game")
        self.assertEqual(app.findall("meta-data"), [])
        self.assertIn("CAR_USES=parked", output)
        self.assertIn("CAR_NEEDS_BRIDGE=no", output)
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
        self.assertIn("android.intent.category.DEFAULT", launcher_categories)
        self.assertIn("android.intent.category.CAR_LAUNCHER", launcher_categories)
        self.assertNotIn("androidx.car.app.category.NAVIGATION", launcher_categories)
        self.assertNotIn("android.intent.category.APP_MAPS", launcher_categories)

    def test_template_selection_does_not_add_car_app_service(self):
        root, output = self.run_patch()
        app = root.find("application")
        self.assertEqual(app.get(f"{A}appCategory"), "game")
        self.assertEqual(app.findall("service"), [])
        self.assertIn("CAR_USES=parked", output)
        self.assertIn("CAR_NEEDS_BRIDGE=no", output)

    def test_removes_existing_projection_discovery(self):
        root, output = self.run_patch(
            "category:com.google.android.gms.car.category.CATEGORY_PROJECTION",
            app_category="game",
        )
        app = root.find("application")
        self.assertEqual(len(app.findall("service")), 1)
        self.assertIsNone(app.find("service").find("intent-filter"))
        self.assertEqual(app.get(f"{A}appCategory"), "game")
        self.assertIn("CAR_USES=parked", output)
        self.assertIn("CAR_NEEDS_BRIDGE=no", output)

    def test_removes_existing_template_discovery(self):
        root, output = self.run_patch("action:androidx.car.app.CarAppService")
        service = root.find("application").find("service")
        self.assertIsNotNone(service)
        self.assertIsNone(service.find("intent-filter"))
        self.assertIn("CAR_USES=parked", output)
        self.assertIn("CAR_NEEDS_BRIDGE=no", output)


if __name__ == "__main__":
    unittest.main()
