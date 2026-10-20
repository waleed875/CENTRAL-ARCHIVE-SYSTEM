# تشغيل المركزي كاملًا بواسطة Docker

هذه الإعدادات للتجربة المحلية على جهاز Windows مع Docker Desktop بوضع Linux containers.
يبني Docker الباك باستخدام Java 21 والفرونت باستخدام Node.js 22؛ لا يلزم تثبيتهما على الجهاز.
نفّذ من جذر النسخة الجديدة `central-unified`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\docker\Start-Archive.ps1
```

أول تشغيل ينشئ `.env.docker` بكلمات مرور ومفاتيح عشوائية، ثم يبني الصور ويشغّل
الخدمات وينتظر جاهزيتها. تشغيل الأمر مجددًا يستخدم القيم والبيانات نفسها.
قد يستغرق البناء الأول عدة دقائق بحسب سرعة التحميل.

| الخدمة | العنوان الافتراضي |
| --- | --- |
| الموقع | http://localhost:3100 |
| Swagger | http://localhost:8180/swagger-ui/index.html |
| جاهزية الباك | http://localhost:8180/actuator/health/readiness |
| لوحة MinIO | http://localhost:9101 |

اسم حساب الموقع `admin` وكلمة المرور هي قيمة `DOCKER_ADMIN_PASSWORD` في `.env.docker`.
حساب MinIO هو `archive_storage` وكلمة مروره `DOCKER_MINIO_PASSWORD`.
تغيير كلمة مرور bootstrap في الملف لا يغيّر حسابًا موجودًا؛ غيّرها من التطبيق.

## متابعة التشغيل

```powershell
docker compose --env-file .env.docker ps
docker compose --env-file .env.docker logs --tail 100 backend
docker compose --env-file .env.docker logs --tail 100 frontend
```

لإيقاف الخدمات مع الاحتفاظ بالبيانات:

```powershell
docker compose --env-file .env.docker down
```

لإعادة البناء والتشغيل بعد تعديل المصدر:

```powershell
docker compose --env-file .env.docker up -d --build --wait --wait-timeout 360
```

إذا لم يكن `docker` ضمن PATH، استخدم سكربت البداية أعلاه؛ يبحث في مسارَي تثبيت
Docker Desktop المعتادين. بقية الأوامر يمكن تنفيذها بالمسار الكامل لـ`docker.exe`.

## البيانات وإعدادات التشغيل

مشروع Compose اسمه `central-unified` ولا يحتوي أسماء حاويات ثابتة. بياناته محفوظة
في volumes مستقلة لـPostgreSQL وMinIO وملفات العمل المؤقتة. بيانات تشغيلك القديم
لا تُنسخ تلقائيًا؛ هذه بيئة جديدة فارغة مع حساب مدير جديد.
احتفظ بملف `.env.docker` مع النسخ الاحتياطية، خصوصًا مفتاح التشفير ومعرّف التثبيت.
الأمر `down` يحتفظ بالـvolumes؛ إضافة `-v` تحذفها، فلا تستخدمها للايقاف المعتاد.

الفرونت يتصل بالباك عبر `http://backend:8080` والباك يتصل بـ`postgres` و`minio`
داخل شبكة Docker. قاعدة البيانات وواجهة S3 غير منشورتين على منافذ الجهاز.
سكربت الباك ينتظر استجابة جاهزية MinIO، ثم ينشئ التطبيق `archive-files` عند الحاجة
باستخدام مكتبة MinIO Java نفسها قبل قبول الجاهزية. يتفعل الإنشاء عبر
`ARCHIVE_STORAGE_INITIALIZE_BUCKET=true` في Compose فقط. رفض المصادقة يظهر
برمز الخادم، مثل `SignatureDoesNotMatch` أو `InvalidAccessKeyId`، دون طباعة الأسرار.

لتغيير المنافذ عدّل قيم `DOCKER_*_PORT` في `.env.docker` ثم أعد التشغيل.
صورة MinIO الافتراضية هي نفس الإصدار المستخدم سابقًا في المستودع؛ يمكن تحديد
`DOCKER_MINIO_IMAGE` في `.env.docker` لصورة متوافقة متاحة محليًا أو في سجل صوركم.

## عند النقل إلى VM أو سيرفر

هذه النسخة تنشر المنافذ على `127.0.0.1` فقط وتستخدم HTTP محليًا.
للنشر المشترك جهّز HTTPS واسم نطاق وreverse proxy، واضبط
`AUTH_COOKIE_SECURE=true` وأصل الموقع في `CORS_ALLOWED_ORIGINS`، وأوقف Swagger
وbootstrap بعد إنشاء حساب الإدارة. احتفظ بالمفاتيح والبيانات الأصلية أثناء التحديث.
اتبع أيضًا [دليل النشر والنسخ الاحتياطي](../../documents/DEPLOYMENT.md) للاستعادة وتشغيل السيرفر.

مراجع: [ترتيب بدء الخدمات](https://docs.docker.com/compose/how-tos/startup-order)،
[خصائص خدمات Compose](https://docs.docker.com/reference/compose-file/services/).
