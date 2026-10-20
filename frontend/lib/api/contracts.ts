import { z } from "zod";

export const apiEnvelopeSchema = z.object({
  success: z.boolean().optional(),
  data: z.unknown().optional(),
  meta: z.object({ message: z.string().optional() }).passthrough().optional(),
}).passthrough();

export const loginSchema = z.object({
  username: z.string().min(1),
  password: z.string().min(1),
});

export type LoginInput = z.infer<typeof loginSchema>;
export type ApiEnvelope = z.infer<typeof apiEnvelopeSchema>;

export type ApiErrorShape = { message: string; status: number };

export const migrationSettingsSchema = z.object({
  migrationEnabled: z.boolean(),
  updatedAt: z.string().nullable().optional(),
  updatedBy: z.string().nullable().optional(),
  environmentDefault: z.boolean(),
});
export type MigrationSettings = z.infer<typeof migrationSettingsSchema>;

export const incompleteProjectReportSchema = z.object({
  totalIncomplete: z.number(),
  totalWithUnknownYear: z.number(),
  projects: z.array(z.object({
    id: z.string(),
    projectNumber: z.string().nullable().optional(),
    name: z.string().nullable().optional(),
    year: z.number().nullable().optional(),
    organizationName: z.string().nullable().optional(),
    status: z.string().nullable().optional(),
    sourceType: z.string().nullable().optional(),
    sourceReference: z.string().nullable().optional(),
    missingFields: z.array(z.string()),
    notes: z.string().nullable().optional(),
    createdAt: z.string().nullable().optional(),
  }).passthrough()),
  fieldCounts: z.array(z.object({ field: z.string(), count: z.number() })),
});
export type IncompleteProjectReport = z.infer<typeof incompleteProjectReportSchema>;
