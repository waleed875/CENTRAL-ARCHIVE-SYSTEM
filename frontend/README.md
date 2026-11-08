# النظام المركزي للأرشيف — الواجهة العربية

واجهة Next.js + TypeScript + React بواجهة عربية RTL وهوية مؤسسية (أزرق ليلي، عاجي، ذهبي)، تعمل كعميل خادمي لواجهة Spring Boot في المستودع نفسه.

## البدء محليًا

يتطلب Node.js 22 أو أحدث، وتشغيل Spring Boot وPostgreSQL وMinIO من المشروع الخلفي.

```bash
npm ci
cp .env.example .env.local
# عدّل BACKEND_URL إذا لم تكن Spring Boot تعمل على localhost:8080
npm run dev
```

افتح http://localhost:3000 ثم سجّل الدخول بحساب موجود في Backend. لا توجد بيانات تجريبية أو حسابات افتراضية ضمن الواجهة.

### إعداد الربط والأمان

- `BACKEND_URL` متغير خادمي خاص بواجهة Next.js. لا تستخدم `NEXT_PUBLIC_BACKEND_URL`.
- في Docker Compose يتصل Next.js بالخدمة عبر `http://backend:8080`. وفي التطوير المحلي الافتراضي `http://localhost:8080`.
- `AUTH_COOKIE_SECURE` يحدد خاصية Secure لملفات تعريف الارتباط. في Docker Compose تضبط القيمة الافتراضية إلى `false` لأن العنوان المحلي HTTP. عند وضع الواجهة خلف HTTPS غيّر القيمة إلى `true` في `.env.docker`.
- `POST /api/session/login` يرسل بيانات المصادقة إلى Spring Boot ويخزن JWT وrefresh token في Cookies آمنة من نوع HttpOnly وSameSite=Lax.
- `POST /api/session/refresh` يطلب زوج رموز جديدًا باستخدام refresh token المخزن خادميًا.
- `POST /api/session/logout` يلغي الجلسة لدى Spring Boot ثم يمسح Cookies.
- `/api/backend/api/v1/...` وسيط يرسل JWT من Cookies إلى Spring Boot دون كشفه لجافاسكربت المتصفح. يدعم الطلبات العادية وملفات multipart وتدفق التنزيلات.
- طبقة Spring Boot هي صاحبة القرار النهائي في الصلاحيات والسرية وقيود المشروع، والواجهة تعرض رسائل الخطأ الفعلية.

### الصفحات

| المسار | المحتوى |
| --- | --- |
| `/` | لوحة العمل، مؤشرات حقيقية، أحدث المشاريع |
| `/organizations` | الجهات والتسلسل الإداري، إنشاء وتعديل |
| `/projects` | البحث والتصفية وإنشاء المشاريع |
| `/projects/[id]` | تفاصيل المشروع وملفاته وتصنيفها وتنزيلها، دورة المراجعة والإصدارات |
| `/reviews` | قائمة انتظار المراجعة والأقدمية |
| `/imports` | استقبال الحزم والتحقق والربط والاستيراد ومتابعة العملية |
| `/exports` | اختيار المشاريع وإنشاء حزمة وتنزيلها |
| `/migration` | مصادر الترحيل والدفعات والتشغيل والمتابعة |
| `/users` | الحسابات وأدوارها وإعادة تعيين كلمات المرور |
| `/audit` | سجل التدقيق الإداري |
| `/settings` | فئات وأنواع الملفات وأنواع المشاريع ومستويات السرية |

### التحقق محليًا

```bash
npm run typecheck
npm run build
node tests/gateway-smoke.mjs
```

يختبر Gateway smoke test جلسات الدخول وتجديدها وتسجيل الخروج، وطلبات API، والحماية من CSRF، ورفع multipart وتنزيل الملفات باستخدام Backend تجريبي. لا يشغّل قاعدة بيانات أو Spring Boot حقيقيًا؛ يلزم اختبار HTTP حقيقي على بيئة معزولة قبل النشر.

### ملاحظات تشغيلية

- يعتمد الاستيراد على `GET /api/v1/packages/:id/references` و`POST /api/v1/packages/:id/import` مع خرائط المراجع ذات الأسماء المحددة في عقد Backend.
- لا يعني استيراد المشروع رفع نسخة SQL مباشرة؛ يجب استعادة القاعدة القديمة في PostgreSQL staging أولًا.
- يعتمد الأمان على الصلاحيات والتحقق في Backend، لا على إخفاء أزرار الواجهة.
