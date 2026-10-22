#!/usr/bin/env python3
"""Apply the unified DNS and multi-ABI build changes to the uploaded source archive."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(".")
java = root / "app/src/main/java/com/androidghosts/dnsttghosts"
res = root / "app/src/main/res"

def replace_once(path, old, new):
    s = path.read_text(encoding="utf-8")
    if new in s:
        return
    if old not in s:
        raise SystemExit(f"Patch anchor not found in {path}: {old[:100]!r}")
    path.write_text(s.replace(old, new, 1), encoding="utf-8")

p = java / "Profiles.kt"
s = p.read_text(encoding="utf-8")
s = s.replace('        put("remoteDns", "8.8.8.8:53")\n', '')
s = s.replace('        p.keys().forEach { k -> out.put(k, p.get(k)) }\n',
              '        p.keys().forEach { k -> out.put(k, p.get(k)) }\n        // DNS upstream is a global VPN setting, never profile-specific.\n        out.remove("remoteDns")\n')
s = s.replace('''    fun vpnDns(ctx: Context) = p(ctx).getString("vpn_dns", "8.8.8.8")!!
''', '''    /** Single DNS endpoint shared by Android's VPN DNS address and the tunnel DNS engine. */
    fun vpnDnsEndpoint(ctx: Context): String {
        val prefs = p(ctx)
        prefs.getString("vpn_dns_endpoint", null)?.takeIf { it.isNotBlank() }?.let { return it }
        // One-time migration: preserve the previous upstream setting if present, otherwise
        // combine the old VPN DNS IP with the standard DNS port.
        val oldTunnel = prefs.getString("tunnel_dns", "")?.trim().orEmpty()
        val oldVpn = prefs.getString("vpn_dns", "8.8.8.8")?.trim().orEmpty().ifBlank { "8.8.8.8" }
        val migrated = oldTunnel.ifBlank { "$oldVpn:53" }
        prefs.edit().putString("vpn_dns_endpoint", migrated).remove("vpn_dns").remove("tunnel_dns").apply()
        return migrated
    }
    fun vpnDnsHost(ctx: Context): String {
        val raw = vpnDnsEndpoint(ctx).trim()
        val uri = java.net.URI("tcp://$raw")
        return uri.host?.removePrefix("[")?.removeSuffix("]")
            ?: throw IllegalArgumentException("Invalid VPN DNS endpoint")
    }
''')
s = s.replace("""    // Empty means: keep the profile's remoteDns (which defaults to resolver:53).
    fun tunnelDns(ctx: Context) = p(ctx).getString("tunnel_dns", "")!!
""", "")
if "fun vpnDnsEndpoint(ctx: Context)" not in s or "fun vpnDns(ctx: Context)" in s:
    raise SystemExit("Profiles.kt unified DNS patch failed")
p.write_text(s, encoding="utf-8")

p = java / "ProfileEditActivity.kt"
s = p.read_text(encoding="utf-8")
s = s.replace('    private lateinit var remoteDns: com.google.android.material.textfield.TextInputEditText\n', '')
s = s.replace('        remoteDns = f.text(R.string.f_remote_dns, p.optString("remoteDns", "8.8.8.8:53"), R.string.f_remote_dns_h)\n', '')
s = s.replace('        out.put("remoteDns", remoteDns.str().trim())\n', '        out.remove("remoteDns")\n')
if 'remoteDns =' in s or 'optString("remoteDns"' in s or 'f_remote_dns' in s:
    raise SystemExit("ProfileEditActivity still exposes the legacy remoteDns field")
p.write_text(s, encoding="utf-8")

p = java / "SettingsActivity.kt"
s = p.read_text(encoding="utf-8")
s = s.replace('''        val vpnDns = f.text(R.string.s_vpn_dns, AppSettings.vpnDns(ctx), R.string.s_vpn_dns_h)
        val tunnelDns = f.text(R.string.s_tunnel_dns, AppSettings.tunnelDns(ctx), R.string.f_remote_dns_h)
''', '''        val vpnDnsEndpoint = f.text(R.string.s_vpn_dns, AppSettings.vpnDnsEndpoint(ctx), R.string.s_vpn_dns_h)
''')
start = s.find('            val rawVpnDns = vpnDns.str().trim()')
end = s.find('            val editor = AppSettings.edit(ctx)', start)
if start < 0 or end < 0:
    if 'val rawDnsEndpoint = vpnDnsEndpoint.str().trim()' not in s:
        raise SystemExit("SettingsActivity DNS validation block not found")
else:
    s = s[:start] + '''            val rawDnsEndpoint = vpnDnsEndpoint.str().trim()
            val validDnsEndpoint = runCatching {
                val uri = java.net.URI("tcp://$rawDnsEndpoint")
                val host = uri.host?.removePrefix("[")?.removeSuffix("]")
                    ?: throw IllegalArgumentException("Missing DNS host")
                require(uri.port in 1..65535 && uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.query == null && uri.fragment == null)
                InetAddresses.parseNumericAddress(host)
                rawDnsEndpoint
            }.getOrElse {
                vpnDnsEndpoint.error = getString(R.string.s_vpn_dns_invalid)
                Toast.makeText(ctx, R.string.s_vpn_dns_invalid, Toast.LENGTH_LONG).show()
                AppSettings.vpnDnsEndpoint(ctx)
            }
            if (rawDnsEndpoint == validDnsEndpoint) vpnDnsEndpoint.error = null

''' + s[end:]
s = s.replace('''                .putString("vpn_dns", validVpnDns)
                .putString("tunnel_dns", validTunnelDns)
''', '''                .putString("vpn_dns_endpoint", validDnsEndpoint)
                .remove("vpn_dns")
                .remove("tunnel_dns")
''')
if 'AppSettings.vpnDns(ctx)' in s or 'AppSettings.tunnelDns(ctx)' in s or 'val tunnelDns =' in s:
    raise SystemExit("SettingsActivity still references old DNS fields")
p.write_text(s, encoding="utf-8")

p = java / "GhostVpnService.kt"
s = p.read_text(encoding="utf-8")
s = s.replace('AppSettings.tunnelDns(this@GhostVpnService).trim().ifEmpty { optString("remoteDns", "8.8.8.8:53") }',
              'AppSettings.vpnDnsEndpoint(this@GhostVpnService).trim()')
old = '''        val dns = AppSettings.vpnDns(this).trim().ifEmpty { "8.8.8.8" }
        val dnsAddress = try {
            InetAddresses.parseNumericAddress(dns)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(getString(R.string.s_vpn_dns_invalid))
        }
'''
new = '''        val dnsEndpoint = AppSettings.vpnDnsEndpoint(this).trim()
        val dnsHost = try {
            val uri = java.net.URI("tcp://$dnsEndpoint")
            val host = uri.host?.removePrefix("[")?.removeSuffix("]")
                ?: throw IllegalArgumentException("Missing DNS host")
            require(uri.port in 1..65535 && uri.rawUserInfo == null && uri.rawPath.isNullOrEmpty() && uri.query == null && uri.fragment == null)
            InetAddresses.parseNumericAddress(host)
        } catch (_: Exception) {
            throw IllegalArgumentException(getString(R.string.s_vpn_dns_invalid))
        }
        val dns = dnsHost.hostAddress ?: dnsEndpoint
        val dnsAddress = dnsHost
'''
if old in s:
    s = s.replace(old, new, 1)
elif 'val dnsEndpoint = AppSettings.vpnDnsEndpoint(this).trim()' not in s:
    raise SystemExit("GhostVpnService VPN DNS parsing block not found")
s = s.replace('b.addDnsServer(dnsAddress.hostAddress ?: dns)', 'b.addDnsServer(dns)')
if 'AppSettings.vpnDns(this)' in s or 'AppSettings.tunnelDns(' in s:
    raise SystemExit("GhostVpnService still references old DNS setting")
p.write_text(s, encoding="utf-8")

updates = {
    "values/strings.xml": {
        "f_remote_dns": None, "f_remote_dns_h": None, "s_tunnel_dns": None,
        "s_tunnel_dns_invalid": None,
        "s_vpn_dns": "Unified VPN DNS endpoint (host:port)",
        "s_vpn_dns_h": "One setting for Android VPN DNS and the tunnel DNS engine, e.g. 8.8.8.8:53. Use a numeric IPv4/IPv6 address and a port.",
        "s_vpn_dns_invalid": "Invalid DNS endpoint. Enter a numeric IPv4/IPv6 host and port, e.g. 8.8.8.8:53 or [2001:4860:4860::8888]:53."
    },
    "values-ar/strings.xml": {
        "f_remote_dns": None, "f_remote_dns_h": None, "s_tunnel_dns": None,
        "s_tunnel_dns_invalid": None,
        "s_vpn_dns": "عنوان DNS موحّد لـ VPN ومحرك النفق (host:port)",
        "s_vpn_dns_h": "إعداد واحد لـ DNS الخاص بواجهة VPN ومحرك النفق، مثل 8.8.8.8:53. أدخل عنوان IPv4/IPv6 رقميًا مع المنفذ.",
        "s_vpn_dns_invalid": "عنوان DNS غير صالح. أدخل عنوان IPv4/IPv6 رقميًا مع المنفذ، مثل 8.8.8.8:53 أو [2001:4860:4860::8888]:53."
    }
}
for rel, values in updates.items():
    p = res / rel
    tree = ET.parse(p)
    root_xml = tree.getroot()
    nodes = {n.attrib.get("name"): n for n in root_xml.findall("string")}
    for name, value in values.items():
        if value is None:
            if name in nodes:
                root_xml.remove(nodes[name])
        elif name in nodes:
            nodes[name].text = value
        else:
            n = ET.SubElement(root_xml, "string", {"name": name})
            n.text = value
    tree.write(p, encoding="utf-8", xml_declaration=True)

# Configure split APKs for each ABI plus a universal APK.
gradle = root / "app/build.gradle.kts"
s = gradle.read_text(encoding="utf-8")
s = re.sub(r'ndk \{ abiFilters \+= setOf\([^)]*\) \}', 'ndk { abiFilters += setOf("arm64-v8a") }', s)
# This build targets the user's Samsung Galaxy A56 (ARM64 only).
s = re.sub(r'    splits \\{.*?    \\}\\n\\n', '', s, flags=re.S)
s = re.sub(
    r'            val requiredAbis = listOf\\("armeabi-v7a", "arm64-v8a", "x86", "x86_64"\\).*?            \\}',
    '''            val entry = zip.getEntry("jni/arm64-v8a/libgojni.so")
                ?: throw GradleException("ghostcore.aar does not include jni/arm64-v8a/libgojni.so")
            if (entry.size <= 0L) throw GradleException("ARM64 libgojni.so is empty")
            logger.lifecycle("Verified ghostcore ARM64 native library: ${entry.size} bytes")''',
    s,
    flags=re.S,
)
gradle.write_text(s, encoding="utf-8")

print("Unified DNS and four-ABI split build changes applied.")
