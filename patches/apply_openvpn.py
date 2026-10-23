#!/usr/bin/env python3
"""Install native OpenVPN profile management into the extracted Android project."""
import re, shutil, sys, xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape

EN = {
"openvpn_title":"OpenVPN tunnel","openvpn_open":"OpenVPN • Import .ovpn",
"openvpn_help":"Import a ready .ovpn profile. Select the profile with its referenced text certificate/key files together. Android allows one VPN tunnel at a time, so disconnect DNSTT first.",
"openvpn_disconnected":"Disconnected","openvpn_connecting":"Connecting to OpenVPN…","openvpn_disconnecting":"Disconnecting…","openvpn_connected":"OpenVPN connected","openvpn_failed":"Connection failed",
"openvpn_traffic":"Received %1$s   •   Sent %2$s","openvpn_import":"Import .ovpn profile / certificates","openvpn_connect":"Connect","openvpn_disconnect":"Disconnect","openvpn_profiles":"Saved profiles",
"openvpn_no_profiles":"No OpenVPN profiles yet. Import a ready .ovpn file to begin.","openvpn_profile_detail":"OpenVPN profile","openvpn_delete_profile":"Delete profile","openvpn_confirm_delete":"Delete “%1$s” from this device?",
"openvpn_disconnect_before_delete":"Disconnect OpenVPN before deleting profiles.","openvpn_select_config":"Select .ovpn/.conf and its referenced text certificate/key files together.",
"openvpn_read_failed":"Could not read %1$s.","openvpn_file_too_large":"%1$s is too large. Maximum size is 5 MB per file.","openvpn_import_failed":"Import failed","openvpn_import_count":"Imported %1$d OpenVPN profile(s).",
"openvpn_invalid_profile":"This file is not a readable OpenVPN client profile.","openvpn_no_remote":"The profile has no remote server entry.","openvpn_server_config":"This looks like a server configuration. Import a client .ovpn profile.",
"openvpn_sidecars_missing":"Profile is not self-contained. Select the .ovpn file with its referenced text certificate/key files. PKCS#12 (.p12/.pfx), CRL and external extra-cert files must be exported inline. Details: %1$s",
"openvpn_choose_profile":"Select an OpenVPN profile first.","openvpn_already_active":"An OpenVPN tunnel is already active or starting. Disconnect it before switching profiles.",
"openvpn_vpn_conflict_title":"Another VPN is active","openvpn_stop_dnstt_first":"Disconnect DNSTT from the main screen, then start OpenVPN. Only one VPN tunnel can run at a time.",
"openvpn_close_other_vpn":"Disconnect the other VPN app first, then retry.","openvpn_permission_denied":"Android VPN permission was not granted.","openvpn_connect_failed":"OpenVPN could not start",
"openvpn_auth_title":"OpenVPN login","openvpn_username":"Username","openvpn_password":"Password","openvpn_continue":"Continue","openvpn_credentials_invalid":"Enter a username and password without line breaks.",
"openvpn_license_notice":"Engine: ics-openvpn / OpenVPN 3. Upstream license and source notices are in OPENVPN-NOTICES.md.",
"select_tunnel":"Select tunnel / البروتوكول","select_dnstt":"DNSTT tunnel","select_openvpn":"OpenVPN tunnel",
"openvpn_switch_disconnect_first":"OpenVPN is active. Disconnect it before switching to DNSTT.",
"openvpn_switch_disconnect_timeout":"OpenVPN is still disconnecting. Wait a moment and switch again.",
"openvpn_advanced_title":"Advanced OpenVPN settings","openvpn_advanced_help":"Edit the complete .ovpn configuration. All supported OpenVPN directives are preserved. Incorrect settings can prevent connection.",
"openvpn_save_advanced":"Save settings","openvpn_advanced_saved":"Advanced profile settings saved.",
"openvpn_advanced_disconnect_first":"Disconnect OpenVPN before editing advanced settings."
"openvpn_full_config_editor":"Edit full .ovpn configuration",
"openvpn_transport_assistant":"Transport / DPI compatibility",
"openvpn_transport_warning":"This adjusts OpenVPN transport settings only. TCP/443 is not a guaranteed DPI bypass; the server must support the selected protocol/port, and real obfuscation requires compatible server-side wrapping.",
"openvpn_transport_label":"Transport protocol",
"openvpn_transport_udp":"UDP (usually faster)",
"openvpn_transport_tcp":"TCP (often 443)",
"openvpn_remote_port":"Server port (1–65535)",
"openvpn_tun_mtu":"TUN MTU (1200–9000)",
"openvpn_mssfix":"UDP MSS fix (500–1460)",
"openvpn_transport_invalid":"Check port, MTU, and MSS values.",
}
AR = {
"openvpn_title":"نفق OpenVPN","openvpn_open":"OpenVPN • استيراد .ovpn",
"openvpn_help":"استورد ملف .ovpn جاهزًا مع ملفات الشهادة والمفتاح النصية المرتبطة به. يسمح Android بنفق VPN واحد فقط؛ افصل DNSTT أولًا.",
"openvpn_disconnected":"غير متصل","openvpn_connecting":"جارٍ الاتصال بـ OpenVPN…","openvpn_disconnecting":"جارٍ قطع الاتصال…","openvpn_connected":"OpenVPN متصل","openvpn_failed":"فشل الاتصال",
"openvpn_traffic":"المستلم %1$s   •   المرسل %2$s","openvpn_import":"استيراد ملف .ovpn / الشهادات","openvpn_connect":"اتصال","openvpn_disconnect":"قطع الاتصال","openvpn_profiles":"الملفات المحفوظة",
"openvpn_no_profiles":"لا توجد ملفات OpenVPN. استورد ملف .ovpn جاهزًا للبدء.","openvpn_profile_detail":"ملف OpenVPN","openvpn_delete_profile":"حذف الملف","openvpn_confirm_delete":"هل تريد حذف «%1$s»؟",
"openvpn_disconnect_before_delete":"افصل OpenVPN قبل حذف الملفات.","openvpn_select_config":"اختر ملف .ovpn/.conf مع ملفات الشهادة والمفتاح النصية المشار إليها.",
"openvpn_read_failed":"تعذرت قراءة %1$s.","openvpn_file_too_large":"الملف %1$s كبير جدًا. الحد الأقصى 5 ميغابايت لكل ملف.","openvpn_import_failed":"فشل الاستيراد","openvpn_import_count":"تم استيراد %1$d من ملفات OpenVPN.",
"openvpn_invalid_profile":"هذا ليس ملف إعداد عميل OpenVPN صالحًا.","openvpn_no_remote":"لا يحتوي الملف على عنوان خادم remote.","openvpn_server_config":"يبدو أن هذا إعداد خادم؛ استورد ملف عميل .ovpn.",
"openvpn_sidecars_missing":"الإعداد غير مكتفٍ ذاتيًا. اختر ملف .ovpn مع ملفات الشهادات والمفاتيح النصية. يجب تحويل ملفات PKCS#12 وCRL والشهادات الإضافية الخارجية إلى إعداد مضمن. التفاصيل: %1$s",
"openvpn_choose_profile":"اختر ملف OpenVPN أولًا.","openvpn_already_active":"يوجد نفق OpenVPN متصل أو قيد التشغيل. افصله قبل تبديل الملف.",
"openvpn_vpn_conflict_title":"يوجد VPN آخر قيد التشغيل","openvpn_stop_dnstt_first":"افصل نفق DNSTT من الشاشة الرئيسية، ثم شغّل OpenVPN. لا يمكن تشغيل نفقين في الوقت نفسه.",
"openvpn_close_other_vpn":"افصل تطبيق VPN الآخر أولًا ثم أعد المحاولة.","openvpn_permission_denied":"لم تتم الموافقة على إذن VPN من Android.","openvpn_connect_failed":"تعذر بدء OpenVPN",
"openvpn_auth_title":"تسجيل دخول OpenVPN","openvpn_username":"اسم المستخدم","openvpn_password":"كلمة المرور","openvpn_continue":"متابعة","openvpn_credentials_invalid":"أدخل اسم المستخدم وكلمة المرور دون أسطر جديدة.",
"openvpn_license_notice":"المحرك: ics-openvpn / OpenVPN 3. تفاصيل الترخيص والسورس في OPENVPN-NOTICES.md.",
"select_tunnel":"اختيار النفق / البروتوكول","select_dnstt":"نفق DNSTT","select_openvpn":"نفق OpenVPN",
"openvpn_switch_disconnect_first":"نفق OpenVPN متصل. افصله قبل التبديل إلى DNSTT.",
"openvpn_switch_disconnect_timeout":"ما زال OpenVPN يفصل الاتصال. انتظر قليلًا ثم حاول التبديل.",
"openvpn_advanced_title":"إعدادات OpenVPN المتقدمة","openvpn_advanced_help":"يمكنك تعديل ملف .ovpn كاملًا مع الحفاظ على توجيهات OpenVPN المدعومة. الإعدادات غير الصحيحة قد تمنع الاتصال.",
"openvpn_save_advanced":"حفظ الإعدادات","openvpn_advanced_saved":"تم حفظ إعدادات الملف المتقدمة.",
"openvpn_advanced_disconnect_first":"افصل OpenVPN قبل تعديل الإعدادات المتقدمة."
"openvpn_full_config_editor":"تعديل ملف .ovpn كاملًا",
"openvpn_transport_assistant":"مساعد النقل والتوافق مع DPI",
"openvpn_transport_warning":"هذه الخيارات تعدّل إعدادات النقل فقط. استخدام TCP/443 لا يضمن تجاوز DPI؛ يجب أن يدعم الخادم البروتوكول والمنفذ، والتعمية الحقيقية تحتاج تغليفًا متوافقًا على الخادم أيضًا.",
"openvpn_transport_label":"بروتوكول النقل",
"openvpn_transport_udp":"UDP (غالبًا أسرع)",
"openvpn_transport_tcp":"TCP (غالبًا 443)",
"openvpn_remote_port":"منفذ الخادم (1–65535)",
"openvpn_tun_mtu":"حجم MTU للنفق (1200–9000)",
"openvpn_mssfix":"ضبط MSS لـ UDP (500–1460)",
"openvpn_transport_invalid":"تحقق من المنفذ وMTU وMSS.",
}
ANDROID="http://schemas.android.com/apk/res/android"
TOOLS="http://schemas.android.com/tools"

def strings(path, mapping):
    src=path.read_text(encoding="utf-8")
    present=set(re.findall(r'<string\b[^>]*\bname=["\']([^"\']+)["\']',src))
    additions=['    <string name="'+k+'">'+escape(v)+'</string>' for k,v in mapping.items() if k not in present]
    if additions:
        m=re.search(r'</resources\s*>',src)
        if not m: raise SystemExit("Missing </resources> in "+str(path))
        src=src[:m.start()]+"\n"+"\n".join(additions)+"\n"+src[m.start():]
        path.write_text(src,encoding="utf-8")
    ET.parse(path)

def main_layout(path):
    # OpenVPN is selected from the toolbar protocol menu, never as a home-screen button.
    src=path.read_text(encoding="utf-8")
    src=re.sub(r'\s*<com\.google\.android\.material\.button\.MaterialButton[^>]*android:id="@\+id/openVpnButton"[^>]*/>', "", src, flags=re.S)
    ET.parse(path)
    path.write_text(src,encoding="utf-8")

def manifest(path):
    ET.register_namespace("android",ANDROID); ET.register_namespace("tools",TOOLS)
    tree=ET.parse(path); root=tree.getroot(); app=root.find("application")
    if app is None: raise SystemExit("Manifest application node missing")
    if not any(n.tag=="activity" and n.get("{"+ANDROID+"}name")==".OpenVPNActivity" for n in app):
        ET.SubElement(app,"activity",{"{"+ANDROID+"}name":".OpenVPNActivity","{"+ANDROID+"}exported":"false","{"+ANDROID+"}windowSoftInputMode":"adjustResize"})
        tree.write(path,encoding="utf-8",xml_declaration=True)
    ET.parse(path)

def gradle(app_path,settings_path):
    src=app_path.read_text(encoding="utf-8")
    if "network.mysterium.openvpn:icsopenvpn:0.7.55-myst" not in src:
        marker='    implementation(files("libs/ghostcore.aar"))'
        if marker not in src: raise SystemExit("ghostcore dependency marker missing; DNSTT cannot be altered")
        src=src.replace(marker,marker+'\n    implementation("network.mysterium.openvpn:icsopenvpn:0.7.55-myst")',1)
    src=re.sub(r'versionCode\s*=\s*\d+',"versionCode = 13",src,count=1)
    src=re.sub(r'versionName\s*=\s*"[^"]+"','versionName = "1.4.9-openvpn-arm64"',src,count=1)
    if "jniLibs.useLegacyPackaging = true" not in src: raise SystemExit("Missing required JNI legacy packaging")
    app_path.write_text(src,encoding="utf-8")
    settings=settings_path.read_text(encoding="utf-8")
    if "openvpn_dart/android/localmaven" not in settings:
        dep=settings.find("dependencyResolutionManagement"); repos=settings.find("repositories {",dep); google=settings.find("        google()",repos)
        if min(dep,repos,google)<0: raise SystemExit("Dependency resolution repositories not found")
        end=settings.find("\n",google)+1
        settings=settings[:end]+'        maven { url = uri(System.getenv("GITHUB_WORKSPACE") + "/openvpn_dart/android/localmaven") }\n'+settings[end:]
        settings_path.write_text(settings,encoding="utf-8")

def main_activity(path, menu_path):
    src=path.read_text(encoding="utf-8")
    if "R.id.m_tunnel" not in src:
        marker='''            R.id.m_about -> MaterialAlertDialogBuilder(this)'''
        if marker not in src: raise SystemExit("MainActivity options menu dispatch point not found")
        src=src.replace(marker,'''            R.id.m_tunnel -> showTunnelChooser()
            R.id.m_about -> MaterialAlertDialogBuilder(this)''',1)
    if "private fun showTunnelChooser()" not in src:
        marker="    override fun onCreateOptionsMenu(menu: Menu): Boolean {"
        if marker not in src: raise SystemExit("MainActivity options menu method not found")
        method='''    private fun showTunnelChooser() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_tunnel)
            .setItems(arrayOf(getString(R.string.select_dnstt), getString(R.string.select_openvpn))) { _, which ->
                if (which == 1) startActivity(Intent(this, OpenVPNActivity::class.java))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

'''
        src=src.replace(marker,method+marker,1)
    path.write_text(src,encoding="utf-8")
    menu=menu_path.read_text(encoding="utf-8")
    if '@+id/m_tunnel' not in menu:
        item='    <item android:id="@+id/m_tunnel" android:title="@string/select_tunnel" android:icon="@drawable/ic_more" app:showAsAction="always" />\n'
        menu=menu.replace("</menu>",item+"</menu>")
        menu_path.write_text(menu,encoding="utf-8")
    ET.parse(menu_path)

def run():
    if len(sys.argv)!=3: raise SystemExit("Usage: apply_openvpn.py PROJECT_DIR PATCH_DIR")
    project=Path(sys.argv[1]).resolve(); patches=Path(sys.argv[2]).resolve()
    java=project/"app/src/main/java/com/androidghosts/dnsttghosts"; layout=project/"app/src/main/res/layout"
    if not java.is_dir() or not layout.is_dir(): raise SystemExit("Android Kotlin/layout folder missing")
    for filename,target in (("OpenVPNEngine.kt",java/"OpenVPNEngine.kt"),("OpenVPNActivity.kt",java/"OpenVPNActivity.kt"),("activity_openvpn.xml",layout/"activity_openvpn.xml")):
        source=patches/filename
        if not source.is_file(): raise SystemExit("Missing patch source: "+str(source))
        shutil.copyfile(source,target)
    main_layout(layout/"activity_main.xml")
    main_activity(java/"MainActivity.kt",project/"app/src/main/res/menu/main.xml")
    manifest(project/"app/src/main/AndroidManifest.xml")
    gradle(project/"app/build.gradle.kts",project/"settings.gradle.kts")
    strings(project/"app/src/main/res/values/strings.xml",EN)
    strings(project/"app/src/main/res/values-ar/strings.xml",AR)
    ET.parse(layout/"activity_openvpn.xml")
    if not (project/"app/libs/ghostcore.aar").is_file(): raise SystemExit("Existing DNSTT AAR missing; aborting")
    if "@+id/openVpnButton" in (layout/"activity_main.xml").read_text(encoding="utf-8"): raise SystemExit("OpenVPN must be selected from the toolbar menu, not the home screen")
    print("OpenVPN integration applied; DNSTT ghostcore AAR retained.")

if __name__=="__main__": run()
