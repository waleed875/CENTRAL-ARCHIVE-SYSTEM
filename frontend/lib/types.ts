export type ApiEnvelope<T> = { success: boolean; data: T; meta?: { message?: string } };
export type PageData<T> = { data: T[]; page: number; limit: number; total: number; totalPages: number };
export type Role = "system_admin" | "central_data_entry" | "auditor" | "archive_officer" | "import_officer" | "export_officer" | "read_only";
export type UserInfo = { id: string; username: string; fullName: string; organization?: string | null; roles: string[]; maxConfidentialityLevelId?: string | null };
export type ProjectStatus = "DRAFT" | "IMPORTED" | "UNDER_REVIEW" | "NEEDS_CORRECTION" | "APPROVED" | "REJECTED" | "ARCHIVED";
export type Project = { id:string; projectUid:string; versionNo:number; previousProjectId?:string|null; isLatestVersion:boolean; organizationId:string; projectTypeId?:string|null; confidentialityLevelId?:string|null; projectNumber:string; projectCode?:string|null; name:string; year:number; executingEntity?:string|null; supervisorName?:string|null; description?:string|null; notes?:string|null; keywords?:string|null; status:ProjectStatus; dataCompletionStatus:"COMPLETE"|"INCOMPLETE"; reviewNotes?:string|null; rejectionReason?:string|null; sourceType?:string; createdBy?:string|null; submittedBy?:string|null; submittedAt?:string|null; approvedBy?:string|null; approvedAt?:string|null; createdAt:string; updatedAt?:string; accessibleFileCount?:number|null };
export type Organization = { id:string; parentId?:string|null; name:string; code:string; type:string; description?:string|null; isActive:boolean; childrenCount:number; projectsCount:number };
export type ArchiveFile = { id:string; projectId:string; categoryId:string; categoryIds?:string[]; categoryNames?:string[]; confidentialityLevelId?:string|null; typeId?:string|null; categoryName?:string|null; typeName?:string|null; originalName:string; mimeType?:string|null; sizeBytes?:number|null; status:string; createdAt:string };
export type FileCategory = { id:string; name:string; code:string; isActive:boolean };
export type FileType = { id:string; categoryId:string; name:string; code:string; defaultExtension?:string|null; isActive:boolean };
export type Reference = { id:string; name:string; code?:string; level?:number; isActive?:boolean };
export type ArchivePackage = { id:string; packageName:string; status:string; projectsCount:number; filesCount:number; receivedAt:string; errorMessage?:string|null; encrypted?:boolean };
export type ExportPackage = { id:string; packageName:string; status:string; projectsCount:number; filesCount:number; createdAt:string };
export type MigrationSource = { id:string; name:string; officeName:string; sourceType:string; host?:string|null; port?:number|null; username?:string|null; databaseName?:string|null; description?:string|null; hasStoredConnection?:boolean; isActive?:boolean; batchesCount?:number };
export type MigrationBatch = { id:string; migrationSourceId:string; batchNumber:string; status:string; projectsCount?:number; filesCount?:number; totalProjectsCount?:number; totalFilesCount?:number; remainingProjectsCount?:number; remainingFilesCount?:number; cancelRequested?:boolean; startedAt?:string; completedAt?:string; notes?:string };
export type DashboardStats = { organizations?:{total:number;active:number}; projects?:{total:number;byStatus:Record<string,number>}; files?:{total:number;totalSizeBytes:number}; pendingReviews?:number; packages?:{received:number;pendingImport:number} };
export const statusNames:Record<string,string>={DRAFT:"مسودة",IMPORTED:"مستورد",UNDER_REVIEW:"قيد المراجعة",NEEDS_CORRECTION:"بحاجة إلى تصحيح",APPROVED:"معتمد",REJECTED:"مرفوض",ARCHIVED:"مؤرشف",RECEIVED:"مستلم",VERIFIED:"تم التحقق",FAILED:"فشل",PARTIAL:"مكتمل جزئيًا",SUCCESS:"ناجح",RUNNING:"جارٍ التنفيذ",CANCELLED:"أُوقف بأمان",PLANNED:"مخطط",ACTIVE:"نشط",INACTIVE:"غير نشط",COMPLETE:"مكتمل",INCOMPLETE:"غير مكتمل",CREATED:"تم الإنشاء",STARTED:"بدأ التنفيذ",COMPANY:"مؤسسة",FACTORY:"مصنع",LINE:"خط",DEPARTMENT:"قسم"};
export function statusName(value?:string|null){return value?statusNames[value]??value:"—"}
export function dateLabel(value?:string|null){if(!value)return"—";const d=new Date(value);return Number.isNaN(d.getTime())?value:new Intl.DateTimeFormat("ar", {day:"numeric",month:"short",year:"numeric"}).format(d)}
export function sizeLabel(bytes?:number|null){if(bytes==null)return"—";if(bytes<1024)return bytes+" بايت";return bytes<1048576?(bytes/1024).toFixed(1)+" ك.ب":(bytes/1048576).toFixed(1)+" م.ب"}
export function canWrite(roles:string[],...required:string[]){return roles.includes("system_admin")||required.some(role=>roles.includes(role))}

export type ProjectMemberDraft={personName:string;organizationName:string;roleName:string;notes:string};
export type ProjectMember=ProjectMemberDraft & {id:string;projectId:string;createdAt:string};
