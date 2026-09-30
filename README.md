# Central Archive System — Backend

واجهة REST خلفية لنظام أرشفة مركزي. التطبيق مبني بـ Java 21 وSpring Boot ويستخدم PostgreSQL للبيانات الوصفية وMinIO لحفظ الملفات. لا يحتوي هذا المستودع على واجهة أمامية.

## المتطلبات

- Docker Desktop مع Docker Compose.
- JDK 21. يُفضّل تشغيل التطبيق بواسطة Maven Wrapper المرفق، فلا تحتاج إلى تثبيت Maven يدوياً.
- PowerShell على Windows، أو أي صدفة تدعم متغيرات البيئة على Linux/macOS.

## التشغيل المحلي السريع

نفّذ الأوامر التالية من جذر المشروع (`CENTRAL-ARCHIVE-SYSTEM`):

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
```

في إعداد الجهاز الحالي لا تحتاج إلى تشغيل Docker Compose: التطبيق يستخدم PostgreSQL المحلي على `localhost:5433` وMinIO الموجود على `localhost:9000`. يقرأ Spring ملف `.env` تلقائياً عند تشغيله من مجلد `backend/Archive/Archive`.

الأمر السابق يشغّل PostgreSQL فقط، حتى لا يتعارض مع MinIO الموجود لديك. اضبط القيم التالية في `.env` لتطابق بيانات MinIO الحالي:

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

إذا لم يكن MinIO موجوداً مسبقاً وتريد تشغيل النسخة المرفقة بالمشروع، استخدم profile الاختياري التالي بدلاً من الأمر السابق:

```powershell
docker compose --profile bundled-minio up -d
```

إذا احتجت PostgreSQL معزولاً داخل Docker بدلاً من الخدمة المحلية الحالية، فهو اختياري ويعمل على `5434` لتجنب التعارض:

```powershell
docker compose --profile bundled-postgres up -d postgres
```

عند استخدامه غيّر `DB_URL` في `.env` إلى `jdbc:postgresql://localhost:5434/central_archive`، وغيّر `DB_USERNAME` و`DB_PASSWORD` إلى قيم `POSTGRES_USER` و`POSTGRES_PASSWORD`.

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

ملف `Central_Platform_ERD/central_platform_mvp_postgresql.sql` يبقى مرجع تصميم، لكنه لم يعد آلية التهيئة التشغيلية في Docker أو الإنتاج.

للتحقق من المخططات:

```powershell
docker compose exec postgres psql -U archive_user -d central_archive -c "\dn"
```

لإعادة إنشاء **بيئة التطوير المحلية فقط** من الصفر (يحذف كل بيانات PostgreSQL وMinIO المحلية):

```powershell
docker compose down -v
docker compose --profile bundled-postgres --profile bundled-minio up -d
```

## MinIO والملفات

استخدم بيانات MinIO الحالية للدخول إلى لوحة MinIO على `http://localhost:9001`. إذا شغّلت profile `bundled-minio`، استخدم `MINIO_ROOT_USER` و`MINIO_ROOT_PASSWORD` من `.env`.

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

للنشر الإنتاجي والنسخ الاحتياطي والاستعادة راجع [DEPLOYMENT.md](DEPLOYMENT.md).

## هيكل backend

```text
backend/Archive/Archive/
├── src/main/java/       # Controllers, services, JPA entities, security
├── src/main/resources/ # application.properties
├── pom.xml
└── mvnw.cmd
```

توجد مجموعات Postman في مجلد [postman](postman) وتصميم الـAPI في [API_ENDPOINTS_DESIGN.md](Central_Platform_ERD/API_ENDPOINTS_DESIGN.md).

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
