import { readJsonWithTimeout, ReadTimeoutError } from "./read-json";
import type { AccountIdentity, AdminSession, DeploymentConfiguration } from "@/lib/types";
import { currentServerTranslation, currentTranslation } from "@/lib/i18n";

const statusMessage = (status: number) => `${currentTranslation("请求失败，状态码")}${status}${currentTranslation("。")}`;

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export function normalizeBaseUrl(value: string) {
  return value.trim().replace(/\/+$/, "");
}

export async function requestJson<T>(session: AdminSession, path: string): Promise<T> {
  let response: Response;
  let payload: unknown;
  try {
    ({ response, payload } = await readJsonWithTimeout(
      `${normalizeBaseUrl(session.apiBaseUrl)}${path}`,
      { credentials: "include" },
    ));
  } catch (error) {
    throw new ApiError(0, currentTranslation(error instanceof ReadTimeoutError
      ? "连接 API 超时，请确认服务器已启动及 API 地址和端口可达。"
      : "无法读取 API。请检查 API 地址、管理台允许来源（CORS），以及 HTTPS 页面是否连接了 HTTP API。"));
  }

  if (!response.ok) {
    const message =
      response.status === 401
        ? currentTranslation("登录会话已失效。")
        : response.status === 404 && path === "/auth/me"
          ? currentTranslation("API 不支持账户接口。请更新 API 与 Web 两个镜像，或检查是否误填了 Web 管理台地址。")
          : statusMessage(response.status);
    throw new ApiError(response.status, message);
  }

  return payload as T;
}

export async function postJson<T>(session: AdminSession, path: string, body: unknown): Promise<T> {
  return mutateJson<T>(session, path, "POST", body);
}

export async function fetchIdentity(session: AdminSession): Promise<AccountIdentity> {
  return requestJson<AccountIdentity>(session, "/auth/me");
}

export async function saveDeploymentConfiguration(
  session: AdminSession,
  body: { api_token: string; admin_web_origins: string[]; admin_web_url: string; web_inventory_enabled: boolean },
): Promise<DeploymentConfiguration> {
  let response: Response;
  try {
    response = await fetch(`${normalizeBaseUrl(session.apiBaseUrl)}/setup/config`, {
      method: "POST",
      credentials: "include",
      headers: { "X-CSRF-Token": session.csrfToken, "Content-Type": "application/json" },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    throw new ApiError(0, currentTranslation("无法确认配置是否已保存。请重新登录并读取服务端配置后再决定是否重试。"));
  }
  const result = await response.json().catch(() => ({}));
  if (!response.ok) {
    const detail = typeof result.detail === "string" ? result.detail : statusMessage(response.status);
    throw new ApiError(response.status, detail);
  }
  return result as DeploymentConfiguration;
}

export async function putJson<T>(session: AdminSession, path: string, body: unknown): Promise<T> {
  return mutateJson<T>(session, path, "PUT", body);
}

export async function patchJson<T>(session: AdminSession, path: string, body: unknown): Promise<T> {
  return mutateJson<T>(session, path, "PATCH", body);
}

async function mutateJson<T>(session: AdminSession, path: string, method: "POST" | "PUT" | "PATCH" | "DELETE", body: unknown): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${normalizeBaseUrl(session.apiBaseUrl)}${path}`, {
      method,
      credentials: "include",
      headers: {
        "X-CSRF-Token": session.csrfToken,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    throw new ApiError(0, currentTranslation("网络请求未能确认结果。请保留当前表单并重试，或刷新库存核对最终状态。"));
  }
  if (!response.ok) {
    if (path.startsWith("/admin-api/accounts")) {
      const result = await response.json().catch(() => ({})) as { detail?: unknown };
      if (typeof result.detail === "string") throw new ApiError(response.status, currentServerTranslation(result.detail));
    }
    const messages: Record<number, string> = {
      401: currentTranslation("登录会话已失效。"),
      403: currentTranslation("Web 库存操作尚未启用。"),
      404: currentTranslation("目标已不存在，请刷新后重试。"),
      409: currentTranslation("数据已被其他设备更新，请刷新后重新提交。"),
      422: currentTranslation("提交内容不完整或格式不正确，请检查表单。"),
    };
    throw new ApiError(
      response.status,
      messages[response.status] ?? statusMessage(response.status),
    );
  }
  return (await response.json()) as T;
}

export async function deleteJson<T>(session: AdminSession, path: string, body?: unknown): Promise<T> {
  return mutateJson<T>(session, path, "DELETE", body);
}
export interface SessionResponse {
  identity: AccountIdentity;
  csrf_token: string;
  expires_at: string | null;
}
export async function openSession(apiBaseUrl: string, apiKey?: string): Promise<SessionResponse> {
  let response: Response; let payload: unknown;
  try {
    ({response, payload} = await readJsonWithTimeout(`${normalizeBaseUrl(apiBaseUrl)}/auth/session`, {
      method: apiKey === undefined ? "GET" : "POST",
      credentials: "include",
      headers: apiKey === undefined ? undefined : { "Content-Type": "application/json" },
      body: apiKey === undefined ? undefined : JSON.stringify({ api_key: apiKey }),
    }));
  } catch { throw new ApiError(0, currentTranslation("无法建立登录会话，请检查 API 地址与允许来源。")); }
  if (!response.ok) throw new ApiError(response.status, currentTranslation(response.status === 401
    ? "登录会话已失效。" : response.status === 404
      ? "服务端不支持会话登录，请更新 API 镜像。" : "无法建立登录会话。"));
  return payload as SessionResponse;
}

export async function previewOrderMetadata<T>(session: AdminSession, file: File, signal: AbortSignal): Promise<T> {
  const body = new FormData();
  body.append("file", file);
  const response = await fetch(`${normalizeBaseUrl(session.apiBaseUrl)}/admin-api/components/order-metadata/preview`, {
    method: "POST", credentials: "include", headers: {"X-CSRF-Token": session.csrfToken}, body, signal,
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new ApiError(response.status, typeof payload.detail === "string" ? currentServerTranslation(payload.detail) : statusMessage(response.status));
  return payload as T;
}
