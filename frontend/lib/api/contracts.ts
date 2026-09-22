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
