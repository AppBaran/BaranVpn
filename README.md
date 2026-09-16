# Baran VPN | باران وی‌پی‌ان 🚀

**قوی‌ترین فیلترشکن روز دنیا برای اندروید**  
**The strongest filter-breaker of the day for Android**

نسخه فعلی: **2.2.3** | Current version: **2.2.3**  
پکیج: `ir.baran.vpn` | حداقل اندروید: 8.0 (API 26)

۱. فایل **[دانلود Baran vpn v 2.2.3 All device.apk](https://github.com/AppBaran/BaranVpn/releases/tag/v2.2.3)** را از بخش انتشار رسمی دریافت کنید.

---

# تمامی پروتکل‌ها کار می‌کنند — مناسب همه اپراتورها — بیش از ۴۳۰ سرور فعال

<p align="center">
  <img src="https://raw.githubusercontent.com/AppBaran/BaranVpn/main/photo_2026-09-14_16-19-16.jpg" width="25%" alt="Baran VPN" />
  <img src="https://raw.githubusercontent.com/AppBaran/BaranVpn/main/photo_2026-09-14_16-19-36.jpg" width="25%" alt="Baran VPN" />
  <img src="https://raw.githubusercontent.com/AppBaran/BaranVpn/main/Screenshot_2026-09-14-16-25-44-027_ir.baran.vpn.jpg" width="25%" alt="Baran VPN" />
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/AppBaran/BaranVpn/main/Screenshot_2026-09-14-16-25-27-397_ir.baran.vpn.jpg" width="25%" alt="Baran VPN" />
  <img src="https://raw.githubusercontent.com/AppBaran/BaranVpn/main/Screenshot_2026-09-14-16-25-13-158_ir.baran.vpn.jpg" width="25%" alt="Baran VPN" />
  <img src="https://raw.githubusercontent.com/AppBaran/BaranVpn/main/Screenshot_2026-09-14-16-24-54-887_ir.baran.vpn.jpg" width="25%" alt="Baran VPN" />
  <img src="https://raw.githubusercontent.com/AppBaran/BaranVpn/main/Screenshot_2026-09-14-15-57-35-409_ir.baran.vpn.jpg" width="25%" alt="Baran VPN" />
</p>

### معرفی
**Baran VPN** یک کلاینت VPN پیشرفته و رایگان برای اندروید است که با تمرکز روی دور زدن سانسور (به‌خصوص در ایران) طراحی شده. هسته اصلی بر پایه **Psiphon**، **Aether** و **Xray (V2ray/Shard)** ساخته شده و از پروتکل‌های مدرن مثل WireGuard، WARP، MASQUE، Tor و V2ray پشتیبانی می‌کند. رابط کاربری مدرن، پشتیبانی کامل از زبان فارسی، و قابلیت‌های حرفه‌ای مثل Split Tunneling و Kill Switch دارد.

### قابلیت‌ها (تمام ویژگی‌ها)

#### پروتکل‌ها و اتصال
- **Smart Connect** (اتصال هوشمند): تست خودکار پروتکل‌ها و انتخاب سریع‌ترین مسیر پایدار
- **WireGuard**: اتصال پایدار با IP ثابت‌تر
- **WARP** (Cloudflare): حریم خصوصی بالاتر + تغییر IP به Cloudflare
- **Psiphon**: حداکثر مقاومت در برابر سانسور + زنجیره‌سازی پروکسی بالادست
- **Tor**: خروج ترافیک از شبکه Tor (با حامل WARP / WireGuard / Direct)
- **MASQUE Transport**
- **V2ray (Shard)**: اتصال مبتنی بر Xray با پینگ خودکار، سابسکرایب، علاقه‌مندی، دستی و تاریخچه
- انتخاب سرور دستی یا خودکار (بهترین پینگ)
- به‌روزرسانی سابسکرایب از داخل اپ
- انتخاب منطقه خروجی (Egress Region) برای Psiphon با نمایش پرچم و تعداد سرورها
- حالت اسکن IP و Obfuscation
- اندپوینت سفارشی (Custom Endpoint)

#### حالت‌های اتصال
- **Device VPN** (وی‌پی‌ان کل دستگاه): تونل کامل برای همه اپ‌ها
- **Proxy** (پروکسی): فقط SOCKS/HTTP محلی — بدون آیکون VPN سیستم
- **Smart Connect**

#### امنیت و پایداری
- **Kill Switch** (مسدودسازی ترافیک در صورت قطع تونل)
- **Auto Reconnect** (اتصال مجدد خودکار)
- مسیریابی DNS خصوصی (Private DNS)
- Fail-closed در خطاهای تونل
- نمایش Exit IP، پینگ و وضعیت Upstream

#### Split Tunneling (تونل اختصاصی)
- حالت Include: فقط اپ‌های انتخاب‌شده از VPN استفاده کنند
- حالت Exclude: اپ‌های انتخاب‌شده VPN را دور بزنند
- انتخاب آسان اپ‌ها با جستجو، Select All / Clear All

#### پروکسی و اشتراک‌گذاری
- پروکسی SOCKS5 محلی (پیش‌فرض `127.0.0.1:1819`)
- امکان تغییر آدرس/پورت
- **LAN Sharing**: اشتراک‌گذاری SOCKS و HTTP برای دستگاه‌های دیگر در شبکه محلی
- پشتیبانی از HTTP CONNECT Proxy (پورت `8080` هنگام فعال بودن LAN)
- LAN پایدار برای WARP، WireGuard و V2ray

#### رابط کاربری و تجربه کاربری
- تم تاریک (Dark Mode) پیش‌فرض
- زبان فارسی و انگلیسی (قابل تغییر)
- انیمیشن‌های مدرن (Connection Orb، Sparkline ترافیک، نئون وضعیت)
- نمایش ترافیک آپلود/دانلود لحظه‌ای + نمودار
- نئون وضعیت اتصال: بنفش / زرد / سبز / قرمز
- لاگ زنده سبک CMD برای V2ray و سایفون
- جدول راهنمای پروتکل‌ها در تنظیمات
- Quick Settings Tile (کاشی تنظیمات سریع برای اتصال/قطع یک‌ضرب)
- پشتیبانی از Android TV / Leanback
- نوتیفیکیشن وضعیت اتصال و به‌روزرسانی

#### به‌روزرسانی و مدیریت
- بررسی و دانلود خودکار آپدیت
- نصب آپدیت داخل اپ (با تأیید کاربر)
- Force Update در صورت نیاز
- نمایش Release Notes

#### تنظیمات پیشرفته
- تنظیم MTU
- سطح لاگ
- Reset to Defaults
- مشاهده و کپی لاگ‌های اپلیکیشن
- راهنمای پروتکل‌ها و پروکسی داخل اپ
- لینک GitHub پروژه و نمایش نسخه برنامه

#### سایر
- لینک مستقیم به کانال تلگرام و اینستاگرام Film Baran
- معرفی برنامه Film Baran (بیش از ۴۰ هزار فیلم و سریال رایگان) با تم زرد و نئون چندرنگ

---

### نکات مهم برای گوشی‌های شیائومی / ردمی / پوکو (MIUI / HyperOS)

سیستم‌عامل MIUI و HyperOS به شدت برنامه‌های پس‌زمینه را می‌کشد و باعث قطع شدن VPN می‌شود. برای اتصال پایدار حتماً این تنظیمات را انجام دهید:

1. **اجازه شروع خودکار (Autostart)**  
   تنظیمات → برنامه‌ها → مدیریت برنامه‌ها → Baran VPN → شروع خودکار → روشن

2. **حذف محدودیت باتری**  
   تنظیمات → باتری → صرفه‌جویی باتری برنامه‌ها (یا Battery saver) → Baran VPN → **بدون محدودیت / No restrictions**

3. **قفل کردن در پس‌زمینه**  
   دکمه Recent Apps را بزنید → روی کارت Baran VPN انگشت را نگه دارید → آیکون قفل را بزنید

4. **اجازه اجرای پس‌زمینه**  
   در اطلاعات برنامه Baran VPN → مجوزها → اجازه اجرای پس‌زمینه و باز شدن در پس‌زمینه را فعال کنید

5. **Always-on VPN (اختیاری اما توصیه می‌شود)**  
   تنظیمات → شبکه و اینترنت → VPN → چرخ‌دنده کنار Baran VPN → Always-on VPN را روشن کنید

6. در صورت نیاز، بهینه‌سازی MIUI را در گزینه‌های توسعه‌دهنده خاموش کنید (درباره گوشی → ۷ بار روی نسخه MIUI بزنید → گزینه‌های توسعه‌دهنده).

پس از این تنظیمات، اتصال باران روی ردمی بسیار پایدار خواهد بود.

---

### نصب
فایل APK را از بخش انتشار رسمی دریافت کنید:
- [`Baran vpn v 2.2.3 All device.apk`](https://github.com/AppBaran/BaranVpn/releases/tag/v2.2.3)

یا از کانال تلگرام: [@appFilmBaran](https://t.me/appFilmBaran)

---

## English

### Introduction
**Baran VPN** is a powerful, free Android VPN client focused on censorship circumvention. It is built around **Psiphon**, **Aether**, and **Xray (V2ray/Shard)** and supports modern protocols including WireGuard, Cloudflare WARP, MASQUE, Tor, and V2ray. It features a modern UI, full Persian & English support, Split Tunneling, Kill Switch, and many advanced options.

### Features (Complete List)

#### Protocols & Connection
- **Smart Connect**: Automatically tests protocols and picks the fastest reliable route
- **WireGuard**: Stable connection with more consistent IP
- **WARP** (Cloudflare): Enhanced privacy + IP changes to Cloudflare
- **Psiphon**: Maximum censorship resistance with upstream proxy chaining
- **Tor**: Traffic exits via Tor network (carrier: WARP / WireGuard / Direct)
- **MASQUE Transport**
- **V2ray (Shard)**: Xray-based connection with auto ping, subscription, favorites, manual and history lists
- Manual server pick or auto best-ping selection
- In-app subscription refresh
- Egress Region selection for Psiphon (with flags and server counts)
- IP Scan mode & Obfuscation
- Custom Endpoint support

#### Connection Modes
- **Device VPN**: full-device tunnel for all apps
- **Proxy**: local SOCKS/HTTP only — no system VPN icon
- **Smart Connect**

#### Security & Reliability
- **Kill Switch** (blocks traffic if the tunnel drops)
- **Auto Reconnect**
- Private DNS routing
- Fail-closed behavior on tunnel errors
- Exit IP display, latency/ping, and Upstream status

#### Split Tunneling
- Include mode: only selected apps use the VPN
- Exclude mode: selected apps bypass the VPN
- Easy app picker with search, Select All / Clear All

#### Proxy & Sharing
- Local SOCKS5 proxy (default `127.0.0.1:1819`)
- Configurable address/port
- **LAN Sharing**: share SOCKS and HTTP with other devices on your local network
- HTTP CONNECT proxy support (port `8080` when LAN sharing is enabled)
- Reliable LAN Sharing for WARP, WireGuard, and V2ray

#### UI & UX
- Dark theme by default
- Persian & English languages
- Modern animations (Connection Orb, traffic sparklines, status neon)
- Real-time upload/download traffic + charts
- Connection-state neon: purple / yellow / green / red
- CMD-style live logs for V2ray and Psiphon
- Protocol comparison table in Settings
- Quick Settings Tile for one-tap connect/disconnect
- Android TV / Leanback support
- Connection & update notifications

#### Updates & Management
- Automatic update checking and download
- In-app update installation
- Force update when required
- Release notes display

#### Advanced Settings
- MTU configuration
- Log level
- Reset to defaults
- View & copy application logs
- Built-in protocol and proxy guides
- Project GitHub link and app version display

#### Other
- Direct links to Telegram & Instagram channels
- Promotion of Film Baran app (40,000+ free movies & series) with yellow theme and multi-color neon

---

### Important Notes for Xiaomi / Redmi / Poco (MIUI / HyperOS)

MIUI and HyperOS aggressively kill background processes, which causes VPN disconnections. Follow these steps for a stable connection:

1. **Enable Autostart**  
   Settings → Apps → Manage apps → Baran VPN → Autostart → Enable

2. **Disable Battery Restrictions**  
   Settings → Battery → App battery saver (or Battery saver) → Baran VPN → **No restrictions**

3. **Lock in Recents**  
   Open Recent Apps → long-press the Baran VPN card → tap the lock icon

4. **Allow Background Activity**  
   In Baran VPN app info → Permissions → enable background launch / run in background

5. **Always-on VPN (recommended)**  
   Settings → Network & internet → VPN → gear icon next to Baran VPN → turn on Always-on VPN

6. Optionally disable MIUI optimization in Developer options (About phone → tap MIUI version 7 times → Developer options).

After these settings, Baran VPN will stay connected reliably on Redmi devices.

---

### Installation
Download the APK from the official release:
- [`Baran vpn v 2.2.3 All device.apk`](https://github.com/AppBaran/BaranVpn/releases/tag/v2.2.3)

Or from the Telegram channel: [@appFilmBaran](https://t.me/appFilmBaran)

---

## Technical Details / جزئیات فنی

| Item | Value |
|------|-------|
| Package | `ir.baran.vpn` |
| Version | 2.2.3 |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 |
| Core | Psiphon Tunnel + Aether + Xray (V2ray/Shard) |
| Architectures | armeabi-v7a, arm64-v8a, x86_64 (+ universal) |
| Languages | Persian (fa), English |
| Psiphon | V2.0.41 |
| Aether | V2.0.0 |

---

## License

This project is licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**.  
See the [LICENSE](LICENSE) file for details.

## Links

- Telegram: [https://t.me/appFilmBaran](https://t.me/appFilmBaran)
- Instagram: [https://instagram.com/appfilmBaran](https://instagram.com/appfilmBaran)
- GitHub: [https://github.com/AppBaran/BaranVpn](https://github.com/AppBaran/BaranVpn)

---

**Made with ❤️ for free internet access**  
**ساخته‌شده با ❤️ برای دسترسی آزاد به اینترنت**
