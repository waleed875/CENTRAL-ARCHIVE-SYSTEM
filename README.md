# Central Archive System

نظام أرشفة مركزي يجمع واجهة Next.js داخل `frontend/` والباك Java 21 وSpring Boot داخل `backend/`، مع PostgreSQL للبيانات الوصفية وMinIO لحفظ الملفات.

## تشغيل النظام كاملًا بـDocker

لسحب نسخة التجميع على جهاز جديد:

```powershell
git clone --branch chore/unified-docker https://github.com/abdullahhabbar-hub/CENTRAL-ARCHIVE-SYSTEM.git central-archive
cd central-archive
```

افتح Docker Desktop بوضع Linux containers، ومن جذر المستودع نفّذ:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\deploy\docker\Start-Archive.ps1
```

الموقع على `http://localhost:3100` وSwagger على `http://localhost:8180/swagger-ui/index.html`.
اسم المستخدم `admin` وكلمة مروره هي قيمة `DOCKER_ADMIN_PASSWORD` داخل `.env.docker`
الذي يولّده السكربت مرة واحدة. هذه بيئة مستقلة بقاعدة جديدة، ولا تنقل بيانات نسختك القديمة.
لا يلزم تثبيت Java أو Node.js لتشغيل Docker. التفاصيل وأوامر الإيقاف والتحديث في
[دليل Docker](deploy/docker/README.md).

لعرض كلمة مرور المدير محليًا:

```powershell
Select-String -Path .env.docker -Pattern '^DOCKER_ADMIN_PASSWORD='
```

انسخ القيمة بعد أول `=` فقط. لكل تثبيت جديد كلمة مرور ومفاتيح مستقلة؛ ملف
`.env.docker` لا يُرفع إلى GitHub. تشغيل السكربت مجددًا يحافظ على الإعدادات والبيانات.

للإيقاف مع الاحتفاظ بالبيانات:

```powershell
docker compose --env-file .env.docker down
```

## ترتيب المستودع

```text
CENTRAL-ARCHIVE-SYSTEM/
├── backend/                  # Spring Boot وملفات Docker الخاصة بالباك
├── frontend/                 # Next.js وملفات Docker الخاصة بالفرونت
├── deploy/docker/            # سكربت البداية ودليل التشغيل
├── documents/               # التقارير ومخططات ERD وسير العمل
├── postman/                 # مجموعات اختبار الـAPI
├── docker-compose.yml       # تشغيل الخدمات الأربع
└── .env.docker.example      # قالب الإعدادات؛ لا يحتوي أسرار تثبيت حقيقية
```

راجع [فهرس المستندات](documents/README.md) للتصميم والتقارير ودليل النشر.

## متطلبات التشغيل اليدوي خارج Docker

- Docker Desktop مع Docker Compose.
- JDK 21. يُفضّل تشغيل التطبيق بواسطة Maven Wrapper المرفق، فلا تحتاج إلى تثبيت Maven يدوياً.
- PowerShell على Windows، أو أي صدفة تدعم متغيرات البيئة على Linux/macOS.

## التشغيل اليدوي للباك مع قاعدة وMinIO خارجيين

نفّذ الأوامر التالية من جذر المشروع (`CENTRAL-ARCHIVE-SYSTEM`):

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

في إعداد الجهاز الحالي لا تحتاج إلى تشغيل Docker Compose: التطبيق يستخدم PostgreSQL المحلي على `localhost:5433` وMinIO الموجود على `localhost:9000`. يقرأ Spring ملف `.env` تلقائياً عند تشغيله من مجلد `backend/Archive/Archive`.

اضبط القيم التالية في `.env` لتطابق بيانات MinIO الحالي:

```text
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=...
MINIO_SECRET_KEY=...
MINIO_BUCKET=archive-files
```

`9001` هو منفذ لوحة الإدارة (Console)، أما التطبيق فيجب أن يتصل بواجهة S3 على `9000`.

الخدمات المستخدمة محلياً:

| الخدمة | العنوان المحلي | الاستخدام |
| --- | --- | --- |
| PostgreSQL | `localhost:5433` | قاعدة بيانات `central_archive` |
| MinIO API | `http://localhost:9000` | تخزين ملفات S3-compatible |
| MinIO Console | `http://localhost:9001` | إدارة MinIO محلياً |

ملف Compose الحالي يشغّل النظام كاملًا، وتبقى قاعدة البيانات وواجهة S3 داخليتين.
إذا أردت التشغيل الكامل اتبع دليل Docker أعلاه؛ وللتشغيل اليدوي استخدم خدماتك الخارجية.

انتظر حتى تصبح خدمة PostgreSQL بحالة `healthy` ثم حمّل إعدادات `.env` إلى جلسة PowerShell الحالية وشغّل الخلفية:

```powershell
Set-Location backend/Archive/Archive
.\mvnw.cmd spring-boot:run
```

التطبيق متاح على `http://localhost:8080`. صفحة Swagger هي `http://localhost:8080/swagger-ui.html`.

## تهيئة قاعدة البيانات

أصبح **Flyway** هو المسؤول الوحيد عن إنشاء وتحديث مخطط PostgreSQL. توجد migrations القابلة للتنفيذ داخل:

`backend/Archive/Archive/src/main/resources/db/migration`

عند تشغيل قاعدة جديدة يطبق Flyway `V1` ثم جميع الإصدارات اللاحقة. بعد ذلك يعمل Hibernate بوضع `validate` فقط للتحقق من مطابقة الـEntities للمخطط، ولا يعدّل قاعدة البيانات تلقائياً.

بالنسبة لقاعدة قديمة أُنشئت قبل Flyway، يمكن تفعيل `FLYWAY_BASELINE_ON_MIGRATE=true` حتى تُسجل كـV1 ثم تطبق migrations التوافقية من V2 فصاعداً. يجب أخذ نسخة احتياطية موثوقة قبل أول ترقية من هذا النوع.

ملف `documents/Central_Platform_ERD/central_platform_mvp_postgresql.sql` يبقى مرجع تصميم، لكنه لم يعد آلية التهيئة التشغيلية في Docker أو الإنتاج.

للتحقق من المخططات:

```powershell
docker compose --env-file .env.docker exec postgres psql -U archive_user -d central_archive -c "\dn"
```

إيقاف بيئة Docker المعتاد موضّح في دليل Docker ويحافظ على البيانات.

## MinIO والملفات

عند التشغيل اليدوي استخدم بيانات MinIO الخارجية. في Docker لوحة MinIO على
`http://localhost:9101` بحساب `archive_storage` وكلمة مرور `DOCKER_MINIO_PASSWORD` من `.env.docker`.

لا يلزم إنشاء bucket يدوياً: ينشئ الـbackend bucket المحدد في `MINIO_BUCKET` (الافتراضي `archive-files`) عند أول رفع ملف. تأكد فقط من أن MinIO يعمل قبل تجربة endpoint الرفع.

## التحقق بعد التشغيل

اختبر استجابة التطبيق:

```powershell
Invoke-WebRequest http://localhost:8080/v3/api-docs | Select-Object StatusCode
```

يسجّل التطبيق حساب مدير ابتدائي مرة واحدة عند تشغيل قاعدة بيانات جديدة. استخدم اسم المستخدم وكلمة المرور من `BOOTSTRAP_ADMIN_USERNAME` و`BOOTSTRAP_ADMIN_PASSWORD` في `.env`. غيّر كلمة المرور في `.env` قبل أول تشغيل؛ تعديلها بعد إنشاء المستخدم لا يغيّر كلمة المرور المحفوظة تلقائياً.

مثال تسجيل الدخول:

```powershell
$body = @{ username = 'admin'; password = 'ChangeThisLocalAdminPassword123!' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/auth/login -ContentType 'application/json' -Body $body
```

## الاختبارات والإيقاف

من `backend/Archive/Archive`:

```powershell
.\mvnw.cmd test
```

لإيقاف الخدمات مع الاحتفاظ بالبيانات المحلية:

```powershell
Set-Location ../../..
docker compose down
```

## الإعدادات والأمان

- ملف `.env` متجاهل من Git. لا ترفع كلمات المرور أو مفتاح JWT إلى المستودع.
- لا تستخدم قيم `.env.example` في بيئة مشتركة أو إنتاجية. استبدل كلمات مرور PostgreSQL وMinIO، ومفتاح `JWT_SECRET`، وكلمة مرور المدير قبل التشغيل الأول.
- `JWT_SECRET` يجب أن يكون Base64 لمفتاح عشوائي طوله 32 بايت على الأقل. مثال: `openssl rand -base64 32`.
- الترحيل من قاعدة البيانات القديمة معطّل افتراضيًا عبر `LEGACY_MIGRATION_ENABLED=false` ويعمل فقط على قاعدة staging المحددة في `LEGACY_DB_*`.
- تشغيل migration يدويًا لا يفعّل الجدولة؛ `LEGACY_MIGRATION_SCHEDULE_ENABLED` يبقى `false` ما لم توجد حاجة تشغيلية صريحة للجدولة.
- المشاريع المرحَّلة تُنشأ بلا مستوى سرية، وملفاتها ترث مستوى مشروعها. لتصنيف مشروع مرحَّل، عدّله من صفحة المشروع بعد الترحيل — التصنيف يُقرأ وقت ترحيل الملفات، فالمشروع المصنَّف مسبقًا يسحب ملفاته معه تلقائيًا.
- لا تفعّل نافذة migration إلا بعد تجهيز staging DB مستقلة، نسخة احتياطية موثوقة، ومساحة spool محلية كافية لمعالجة أكبر ملف legacy بصورة streaming.

للنشر الإنتاجي والنسخ الاحتياطي والاستعادة راجع [DEPLOYMENT.md](documents/DEPLOYMENT.md).

## هيكل backend

```text
backend/Archive/Archive/
├── src/main/java/       # Controllers, services, JPA entities, security
├── src/main/resources/ # application.properties
├── pom.xml
└── mvnw.cmd
```

توجد مجموعات Postman في مجلد [postman](postman) وتصميم الـAPI في [API_ENDPOINTS_DESIGN.md](documents/Central_Platform_ERD/API_ENDPOINTS_DESIGN.md).

## القيم المقيدة للحقول

معظم حقول الحالة والنوع في الـAPI مقيدة بمجموعة قيم ثابتة، وبعضها من نوع enum في Java لا يمكن لعميل مكتوب يدوياً أن يراه. لذلك يوفّر `GET /api/v1/enums` كل هذه المجموعات في استدعاء واحد، مقروء لأي مستخدم مُصادَق عليه:

```json
{
  "success": true,
  "data": {
    "projectStatus": ["DRAFT", "IMPORTED", "UNDER_REVIEW", "NEEDS_CORRECTION", "REJECTED", "APPROVED", "ARCHIVED"],
    "dataCompletionStatus": ["COMPLETE", "INCOMPLETE"],
    "projectSourceType": ["MANUAL", "BRANCH_PACKAGE", "MIGRATION"],
    "userStatus": ["ACTIVE", "DISABLED", "LOCKED"],
    "organizationType": ["COMPANY", "FACTORY", "LINE", "DEPARTMENT"],
    "migrationSourceType": ["POSTGRESQL", "LEGACY_DB"],
    "migrationBatchStatus": ["PLANNED", "RUNNING", "SUCCESS", "FAILED", "PARTIAL"],
    "migrationBatchCompletionStatus": ["SUCCESS", "PARTIAL", "FAILED"],
    "packageStatus": ["RECEIVED", "VERIFIED", "IMPORTED", "FAILED", "DUPLICATE"],
    "fileStatus": ["ACTIVE", "DELETED"],
    "auditAction": ["REJECT", "APPROVE", "ARCHIVE", "IMPORT", "CREATE", "UPDATE", "DELETE", "READ", "EXECUTE"],
    "auditEntityType": ["Auth", "User", "Role", "..."],
    "projectSortFields": ["createdAt", "name", "projectCode", "..."],
    "sortDirections": ["asc", "desc"]
  }
}
```

الاستخدام المقصود أن يبني العميل القوائم المنسدلة من هذه الاستجابة بدل الاحتفاظ بنسخة من القيم عنده. الحقول الغائبة عن الاستجابة نص حر، منها `ProjectMember.roleName`.

ثلاث حالات تحتاج انتباهاً خاصاً:

- **`migrationBatchCompletionStatus`** قائمة منفصلة عن `migrationBatchStatus` لأنها تحتوي الحالات الثلاث التي يقبلها endpoint الإكمال فقط. عرض `PLANNED` أو `RUNNING` في نموذج الإكمال يرفضه الخادم.
- **`projectSortFields` و`sortDirections`** تُقبلان بهدوء. قيمة `sortBy` خارج القائمة لا تُرفض، بل يُرتّب الخادم النتائج حسب `createdAt` بدلاً منها، فعرض القائمة يمنع إعادة ترتيب صامتة.
- **`migrationSourceType`** مقيّد بـenum منذ `V20`. كان الحقل نصاً حراً يُتحقق منه عند تشغيل الدفعة لا عند الحفظ، فكان خطأ إملائي يُخزَّن ويُكتشف متأخراً بعد فتح نافذة الترحيل.
