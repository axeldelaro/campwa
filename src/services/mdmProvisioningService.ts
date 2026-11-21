import QRCode from 'qrcode';

export interface MdmReleaseInfo {
  apkUrl: string;
  signatureChecksum: string;
  releaseTag: string;
  checksumVerified: boolean;
}

export interface MdmQrEnrollmentParams {
  pairCode: string;
  familyKey: string;
  deviceCode: string;
  adminPin: string;
  childName: string;
  wifiSsid?: string;
  wifiPassword?: string;
}

export interface MdmQrEnrollmentResult {
  qrDataUrl: string;
  payloadJson: string;
  releaseInfo: MdmReleaseInfo;
}

const DEFAULT_APK_URL = 'https://github.com/axeldelaro/campwa/releases/latest/download/SentinelCam.apk';
const GITHUB_LATEST_RELEASE_API = 'https://api.github.com/repos/axeldelaro/campwa/releases/latest';
const COMPONENT_NAME = 'com.sentinelcam.app/.receiver.SentinelDeviceAdminReceiver';

let cachedReleaseInfo: { info: MdmReleaseInfo; fetchedAt: number } | null = null;

/**
 * Fetches the live SentinelCam.apk URL and exact Android Enterprise signing-certificate
 * SHA-256 base64url checksum published in the latest GitHub Release.
 */
export async function fetchLiveMdmReleaseInfo(forceRefresh = false): Promise<MdmReleaseInfo> {
  if (!forceRefresh && cachedReleaseInfo && Date.now() - cachedReleaseInfo.fetchedAt < 5 * 60_000) {
    return cachedReleaseInfo.info;
  }

  try {
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), 7000);
    const res = await fetch(GITHUB_LATEST_RELEASE_API, {
      headers: { Accept: 'application/vnd.github+json' },
      signal: controller.signal,
    });
    window.clearTimeout(timer);

    if (res.ok) {
      const data = (await res.json()) as {
        tag_name?: string;
        body?: string;
        assets?: Array<{ name?: string; browser_download_url?: string }>;
      };
      const apkAsset = data.assets?.find((a) => a.name?.toLowerCase() === 'sentinelcam.apk');
      const apkUrl = apkAsset?.browser_download_url || DEFAULT_APK_URL;
      const body = data.body || '';
      const match = body.match(/MDM_SIGNATURE_CHECKSUM:([A-Za-z0-9_-]{40,64})/);
      const checksum = match?.[1]?.trim() || '';

      const info: MdmReleaseInfo = {
        apkUrl,
        signatureChecksum: checksum,
        releaseTag: data.tag_name || 'latest',
        checksumVerified: checksum.length >= 40,
      };
      cachedReleaseInfo = { info, fetchedAt: Date.now() };
      return info;
    }
  } catch {
    // Offline or GitHub API rate-limited fallback
  }

  return {
    apkUrl: DEFAULT_APK_URL,
    signatureChecksum: '',
    releaseTag: 'latest',
    checksumVerified: false,
  };
}

/**
 * Builds the official Google Android Enterprise Fully Managed Device (Device Owner)
 * QR provisioning payload and renders it 100% locally in the browser (no data sent externally).
 */
export async function generateMdmProvisioningQr(
  params: MdmQrEnrollmentParams,
  forceRefresh = false
): Promise<MdmQrEnrollmentResult> {
  const releaseInfo = await fetchLiveMdmReleaseInfo(forceRefresh);

  const payload: Record<string, unknown> = {
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME': COMPONENT_NAME,
    'android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION': releaseInfo.apkUrl,
    'android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED': true,
    'android.app.extra.PROVISIONING_SKIP_ENCRYPTION': false,
    'android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE': {
      pairCode: params.pairCode,
      familyKey: params.familyKey,
      deviceCode: params.deviceCode,
      adminPin: params.adminPin,
      childName: params.childName,
    },
  };

  if (releaseInfo.signatureChecksum) {
    payload['android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM'] =
      releaseInfo.signatureChecksum;
  }

  const cleanSsid = (params.wifiSsid || '').trim();
  const cleanWifiPwd = (params.wifiPassword || '').trim();
  if (cleanSsid.length > 0) {
    payload['android.app.extra.PROVISIONING_WIFI_SSID'] = cleanSsid;
    if (cleanWifiPwd.length > 0) {
      payload['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = 'WPA';
      payload['android.app.extra.PROVISIONING_WIFI_PASSWORD'] = cleanWifiPwd;
    } else {
      payload['android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE'] = 'NONE';
    }
  }

  const payloadJson = JSON.stringify(payload);
  const qrDataUrl = await QRCode.toDataURL(payloadJson, {
    errorCorrectionLevel: 'M',
    margin: 2,
    width: 340,
    color: {
      dark: '#09090b',
      light: '#ffffff',
    },
  });

  return {
    qrDataUrl,
    payloadJson,
    releaseInfo,
  };
}
