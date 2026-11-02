# إصدار نسخة جديدة (Releasing)

## 1) إنشاء مفتاح التوقيع (مرة واحدة فقط)
```bash
keytool -genkeypair -v -keystore sticker-release.jks -alias sticker -keyalg RSA -keysize 4096 -validity 10000
```
- **لا ترفع الملف إلى GitHub أبدًا** (`.gitignore` يمنعه).
- احفظ نسخة احتياطية من `sticker-release.jks` وكلمات المرور في مكانين آمنين خارج المستودع.
- **إن فُقد المفتاح فلن يستطيع أي مستخدم التحديث فوق النسخة القديمة** (يجب إلغاء التثبيت وفقدان البيانات المحلية).

## 2) بصمة الشهادة
```bash
keytool -list -v -keystore sticker-release.jks -alias sticker | grep "SHA256:"
```

## 3) أسرار GitHub (Settings → Secrets and variables → Actions)
| Secret | القيمة |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 sticker-release.jks` |
| `KEYSTORE_PASSWORD` | كلمة مرور الـ keystore |
| `KEY_ALIAS` | `sticker` |
| `KEY_PASSWORD` | كلمة مرور المفتاح |
| `EXPECTED_CERT_SHA256` | (اختياري لكن موصى به) بصمة الشهادة من الخطوة 2 — يفشل البناء لو تغيّر المفتاح |

الـ workflow لا يطبع الأسرار، ويفشل برسالة واضحة لو كانت ناقصة.

## 4) نشر إصدار
1. ارفع `APP_VERSION_CODE` (+1 على الأقل) وعدّل `APP_VERSION_NAME` في `gradle.properties`، وحدّث `CHANGELOG.md`.
2. `git tag vX.Y.Z && git push origin vX.Y.Z` (يجب أن يطابق الوسم `APP_VERSION_NAME`).
3. `release.yml` يختبر ويبني ويوقّع ويتحقق بـ `apksigner` ثم ينشر: APK و`.sha256` و`update.json`.
4. التطبيق يقرأ `update.json` ويتحقق من SHA-256 والحجم والحزمة وversionCode وشهادة التوقيع قبل عرض زر التثبيت.

## 5) إعداد حساب المحدّث
ضع اسم حسابك في `UPDATE_REPO_OWNER` بـ `gradle.properties` (أو يمرره الـ CI تلقائيًا). المستودع يجب أن يكون عامًا؛ لا يُستخدم أي توكن داخل التطبيق.

## ملاحظة: نسخ الاختبار (debug) من Actions
نسخ `debug-apk` موقّعة بمفتاح اختبار ثابت وعلني (`app/debug.keystore`، غير سري) حتى تتثبت كل نسخة اختبار فوق السابقة بدون حذف. هذا المفتاح **لا يُستخدم أبدًا للإصدارات الرسمية**. التحويل من نسخة اختبار إلى نسخة رسمية (مفتاح مختلف) يتطلب حذف التطبيق مرة واحدة، وبعدها تتحدث كل الإصدارات الرسمية فوق بعضها بالمفتاح الرسمي.


## نشر تلقائي
كل دفع إلى `main` يغيّر كود التطبيق (وليس الملفات النصية أو `docs/` أو `.github/`) يُنشَر تلقائيًا بعد نجاح الاختبارات:
رقم الإصدار التالي `1.0.<الكود+1>` يُكتب في `gradle.properties` بواسطة الـ workflow، ويُنشر في مستودع التحديثات العام، فيظهر في التطبيق.
- لمنع النشر لدفعة معيّنة: اكتب `[skip release]` في رسالة الـ commit.
- ملاحظات الإصدار تُؤخذ من قسم `## <الإصدار>` في `CHANGELOG.md`، وإن لم يوجد فمن أحدث قسم.
- النشر اليدوي (وسم أو Run workflow) ما زال متاحًا.
