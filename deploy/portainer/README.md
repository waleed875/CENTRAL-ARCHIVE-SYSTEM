# نشر النظام من Gitea عبر Portainer دون فقد البيانات

هذا الدليل مخصص لـ **Docker Standalone** مع مستودع Gitea داخلي وPortainer على الشبكة نفسها.
الملف `docker-compose.portainer.yml` في جذر المستودع يختلف عن `docker-compose.yml` المحلي: 
**لا يحتوي على build** ويستخدم صورًا مبنية مسبقًا، ويرفض تشغيل Stack عندما لا تحدد أسماء الـvolumes الموجودة.

> **مهم:** نشر نسخة جديدة من البرنامج لا يساوي استعادة نسخة احتياطية. لا تحذف قاعدة البيانات أو ملفات MinIO، ولا تستخدم `docker compose down -v` أو زر حذف Volumes في Portainer.

## أ. قبل تحديث أي شيء

1. من Portainer افتح البيئة الصحيحة > **Stacks** وسجل اسم الـStack الحالي، وما إذا كان `Docker Standalone` أو `Swarm`، وحالة الخدمات.
2. سجل من **Containers > Inspect > Mounts** أسماء وحدات PostgreSQL وMinIO وbackend الموجودة فعليًا على **Docker host** نفسه.
3. بديل من shell السيرفر:
   
   ```bash
   docker volume ls
   docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
   docker inspect <postgres-container> --format '{{json .Mounts}}'
   docker inspect <minio-container> --format '{{json .Mounts}}'
   ```
4. احتفظ **بنفس** بيانات `DOCKER_POSTGRES_PASSWORD` و`DOCKER_MINIO_PASSWORD` و`DOCKER_JWT_SECRET` و`DOCKER_SETTINGS_ENCRYPTION_KEY` و`DOCKER_ARCHIVE_INSTANCE_UUID` و`DOCKER_ADMIN_PASSWORD`. تجديد مفتاح التشفير يكسر القدرة على فك تشفير الإعدادات المخزنة. لا تضع القيم في Git.
5. خذ نسخة احتياطية **قابلة للاستعادة** من PostgreSQL وMinIO، واحتفظ بإصدار التطبيق ونسخة الملفات من اللحظة نفسها. لا تكتفِ بنسخ ملفات مجلد PostgreSQL وهو يعمل.
6. **لا تنشئ Stack ثانية تعمل بالتوازي فوق البيانات نفسها**. حدّث الـStack الحالي، أو أوقفه وفق نافذة صيانة مُتحكم بها ثم حوّل الإدارة بحذر.

### وحدات التخزين

في `docker-compose.portainer.yml` توجد متغيرات إلزامية:
- `ARCHIVE_POSTGRES_VOLUME`: اسم Volume الذي يحتوي قاعدة PostgreSQL الأصلية.
- `ARCHIVE_MINIO_VOLUME`: اسم Volume الذي يحتوي محتوى MinIO الأصلي.
- `ARCHIVE_BACKEND_VOLUME`: اسم Volume الذي يحتوي بيانات backend والتخزين المؤقت.

تحقّق من الأسماء قبل التشغيل. عند عدم وجود الاسم يرفض Docker تشغيل الخدمة، بدل إنشاء Volume جديدة فارغة. **هذا ليس إجراء استعادة آليًا**.

> لا تغيّر إصدار PostgreSQL الرئيسي بلا خطة ترقية. ملف المثال يستخدم `postgres:17-alpine` كالنسخة المحلية في المستودع؛ تأكد من مطابقة الإصدار في سيرفرك قبل تطبيقه.

## ب. مزامنة كود GitHub إلى Gitea

آخر التحديثات المطلوبة دُمجت في `main` من `waleed875/CENTRAL-ARCHIVE-SYSTEM`. 
تحقق أن نسخة `main` الموجودة في Gitea تحتوي هذه التعديلات قبل بناء الصور؛ **GitHub لا ينسخ تلقائيًا إلى Gitea**.
من جهاز تملك فيه clone المستودع وإذن الدفع إلى Gitea:

```bash
git fetch origin main
git checkout main
git pull --ff-only origin main
git remote -v
# فقط إذا لم تكن قد أضفت ريموت Gitea:
git remote add internal https://<gitea-host>/<owner>/<repo>.git
git push internal main
```

استبدل بيانات الرابط بالقيم الفعلية؛ لا ترفع أسرار البيئة إلى المستودع.

## ج. بناء الصور خارج Portainer

يفترض المثال أن لديك Docker على جهاز build يستطيع قراءة Gitea والوصول إلى Registry الخاصة به.
نفّذ في جذر مشروع Gitea الذي يحتوي آخر `main`:

```bash
export REGISTRY=<gitea-registry-host[:port]>
export OWNER=<gitea-owner>
export TAG=$(git rev-parse --short=12 HEAD)

docker login "$REGISTRY"

docker build -t "$REGISTRY/$OWNER/central-archive-backend:$TAG" ./backend/Archive/Archive
docker build -t "$REGISTRY/$OWNER/central-archive-frontend:$TAG" ./frontend

docker push "$REGISTRY/$OWNER/central-archive-backend:$TAG"
docker push "$REGISTRY/$OWNER/central-archive-frontend:$TAG"
```

احفظ الاسمين النهائيين للصورتين في Portainer:
`ARCHIVE_BACKEND_IMAGE` و`ARCHIVE_FRONTEND_IMAGE`.

يمكن تحويل الأوامر إلى Gitea Actions لاحقًا بعد التأكد من إعداد runner يدعم Docker وصلاحيات الوصول إلى الـRegistry. 
إذا كانت Registry خاصة، أضفها تحت **Portainer > Registries** مع صلاحية قراءة الصور. أصلح شهادة CA الداخلية على Docker host وPortainer بدل تعطيل فحص TLS.

## د. نشر التحديث من Portainer

**على Stack قائم:** حدّث مصدرها Git ليتضمن `docker-compose.portainer.yml`، أو انقل تعريفها بعناية وفق طريقة إدارتها الحالية؛ 
لا تنشئ Stack متوازية تستخدم Volume ذاتها.

**في حالة Git Stack:**
1. تأكد أن Portainer يستطيع الوصول إلى رابط Gitea الداخلي من البيئة التي يعمل فيها (اسم DNS/منفذ/شهادة وثقة Git).
2. اختر Git Repository، والفرع `main`، و**Compose path = `docker-compose.portainer.yml`**.
3. عرّف متغيرات `ARCHIVE_BACKEND_IMAGE` و`ARCHIVE_FRONTEND_IMAGE` والأسرار القديمة، وثلاثة أسماء الـvolumes الفعلية.
4. اضبط `ARCHIVE_PUBLIC_ORIGIN` بعنوان واجهة الموقع (بروتوكول + اسم نطاق + منفذ إن وجد).
5. `AUTH_COOKIE_SECURE=true` عند استخدام HTTPS؛ لن تعمل Cookie مؤمّنة على HTTP. في اختبار داخلي مؤقت عبر HTTP، اضبطه `false` وحافظ على عزل الشبكة.
6. اترك `ARCHIVE_BOOTSTRAP_ADMIN_ENABLED=false` عندما تكون قاعدة البيانات تحتوي حسابات موجودة. اترك `LEGACY_MIGRATION_ENABLED=false` في التحديث الاعتيادي.
7. اختر **Re-pull image / Pull and redeploy**، وراجع Logs وHealth، واحرص على عدم تفعيل حذف الـvolumes.
8. إذا عرضت الواجهة فقط النسخة القديمة: تحقق أن قيم `ARCHIVE_*_IMAGE` تستخدم TAG الجديد؛ تغيير ملفات المصدر أو فرع Git لا يُعيد بناء صورة Docker تلقائيًا.

لإعدادات الإنتاج لا تعرّض Postgres وMinIO API للإنترنت. اجعل الواجهة خلف HTTPS reverse proxy وقيّد منافذ الإدارة.

## هـ. التحقق من نجاح إعادة التشغيل

- تحقق من ظهور المشاريع والملفات السابقة؛ وإذا ظهرت قاعدة جديدة فارغة **أوقف الكتابة فورًا** وافحص أسماء الـvolumes والـstack المُستخدم.
- افتح إعدادات المستخدم للتأكد من التحديثات الجديدة.
- افحص قائمة الملفات المعزولة وجرّب تسجيل قرار KEEP على ملف تجريبي.
- راجع تطبيق Flyway `V36__quarantine_review_history.sql` في سجلات Backend.
- تحقق من معاينة وتنزيل ملف معروف ومن سلامة مفتاح التشفير القديم.
- نفّذ اختبار الإفراج فقط على بيانات staging، لا على ملفات غير معروفة من الإنتاج.

## و. هل يمكن رفع البيانات واستعادتها من Portainer؟

Portainer يتيح **رفع ملفات عادية إلى Volume** عبر Volumes > Browse في بعض البيئات (عند استخدام Agent أو Swarm)،
لكن **لا تستخدم رفع الملفات وحده لاستعادة PostgreSQL**. PostgreSQL يحتاج `pg_restore` أو `psql` لنسخة منطقية متوافقة،
وMinIO يحتاج إجراء استعادة S3 متوافقًا مثل mirror من نسخة موثوقة. حافظ على ترابط النسختين لأن صفوف قاعدة البيانات تشير إلى مفاتيح كائنات في MinIO.

لذلك عند **التحديث على نفس المضيف** لا تحتاج إلى إعادة رفع البيانات أصلًا. 
أما النقل إلى **مضيف مختلف** فيحتاج خطة restore واختبار ومراجعة تطابق PostgreSQL وMinIO وأسرار التشفير.

## ز. تشخيص فشل النشر السابق

| رسالة أو عرض | السبب المحتمل | التحقق |
| --- | --- | --- |
| `Unable to upgrade to tcp, received 200` | Portainer لا يدعم build على Docker remote | استخدم الصور الجاهزة `image:` |
| `repository not found` أو `authentication required` | الصورة لم تُنشر أو Portainer غير موثّق مع Registry | راجع docker login / Registry |
| `x509: certificate signed by unknown authority` | شهادة Gitea/Registry غير موثوقة | ثبّت CA الموثوقة |
| `no such host` / `connection refused` | Portainer لا يصل إلى Gitea، أو استُخدم localhost خطأً | DNS/IP ورابط Gitea من الشبكة الصحيحة |
| `external volume not found` | أسماء Volume خطأ أو على مضيف آخر | `docker volume ls` ومعلومات Mounts |
| كل المشاريع اختفت | Stack جديدة استخدمت قاعدة فارغة | أوقف الكتابة ولا تنفذ ترحيلًا؛ افحص الـvolumes |
| خطأ Flyway أو إعداد التشفير | مفاتيح/نسخة DB غير متوافقة | أوقف النشر، راجع النسخ الاحتياطية والقيم السابقة |

للمراجعة: [Docker Compose المحلية](../../docker-compose.yml) و[دليل النشر والنسخ الاحتياطي](../../documents/DEPLOYMENT.md).
