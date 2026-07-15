import type { NextFunction, Request, RequestHandler, Response } from 'express';

const LOOPBACK_V4_MAPPED = '::ffff:127.';

export function isLoopbackAddress(remoteAddress: string | undefined): boolean {
  if (!remoteAddress) return false;
  const remote = remoteAddress.trim().toLowerCase();
  return remote === '::1'
    || remote === '0:0:0:0:0:0:0:1'
    || remote.startsWith('127.')
    || remote.startsWith(LOOPBACK_V4_MAPPED);
}

export function isAdminRequestAllowed(
  remoteAddress: string | undefined,
  presentedToken: string | undefined,
  configuredToken: string | undefined,
): boolean {
  const expected = (configuredToken || '').trim();
  const actual = (presentedToken || '').trim();
  if (expected) return actual === expected;
  return isLoopbackAddress(remoteAddress);
}

export function createAdminGuard(configuredToken = process.env.BFF_ADMIN_TOKEN || ''): RequestHandler {
  return (req: Request, res: Response, next: NextFunction): void => {
    if (isAdminRequestAllowed(req.socket.remoteAddress, req.header('X-Admin-Token'), configuredToken)) {
      next();
      return;
    }
    res.status(403).json({
      success: false,
      msg: configuredToken.trim()
        ? 'X-Admin-Token does not match BFF_ADMIN_TOKEN'
        : 'BFF_ADMIN_TOKEN is not configured; non-loopback requests are denied',
    });
  };
}

export const requireBffAdmin = createAdminGuard();
