import React, { useState, useEffect } from 'react';

interface UserInfo {
  authenticated: boolean;
  userId: string;
  username: string;
  email: string;
  roles: string[];
}

interface YandexProfile {
  accountId: string;
  login: string | null;
  email: string | null;
  fullName: string | null;
}

interface YandexState {
  yandex: boolean;
  consent: 'PENDING' | 'GRANTED' | 'DENIED';
  consentedAt: string | null;
  profile: YandexProfile;
}

interface DailyMetrics {
  day: string;
  avgBatteryLevel: number;
  minBatteryLevel: number;
  avgSignalQuality: number;
  totalMovements: number;
  totalGestures: number;
  totalRecognitionErrors: number;
  maxTemperatureC: number;
  errorCodes: string[];
  measurements: number;
}

interface ProsthesisSummary {
  days: number;
  avgBatteryLevel: number;
  avgSignalQuality: number;
  totalMovements: number;
  totalGestures: number;
  recognitionErrorRate: number;
  maxTemperatureC: number;
  errorCodes: string[];
}

interface ProsthesisReport {
  prosthesisId: number;
  serial: string;
  model: string;
  summary: ProsthesisSummary;
  daily: DailyMetrics[];
}

interface ReportData {
  customer: { id: number; fullName: string; email: string };
  period: { from: string; to: string };
  dataAvailableUntil: string;
  prostheses: ProsthesisReport[];
}

const ReportPage: React.FC = () => {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [yandex, setYandex] = useState<YandexState | null>(null);
  const [consentBusy, setConsentBusy] = useState(false);

  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [report, setReport] = useState<ReportData | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);

  useEffect(() => {
    const run = async () => {
      try {
        const response = await fetch('/api/users/me', {
          credentials: 'include',
        });
        if (response.ok) {
          const data: UserInfo = await response.json();
          setUser(data);
          // Yandex-профиль актуален только для пользователей, вошедших через Yandex ID
          const yr = await fetch('/api/users/me/yandex', {
            credentials: 'include',
          });
          if (yr.status === 200) {
            setYandex(await yr.json());
          } else if (yr.status === 404) {
            setYandex(null);
          }
        } else {
          setUser(null);
          setYandex(null);
        }
      } catch {
        setUser(null);
        setYandex(null);
      }
    };
    run();
  }, []);

  const handleLogin = () => {
    window.location.href = '/oauth2/authorization/keycloak';
  };

  const handleLogout = async () => {
    try {
      await fetch('/logout', {
        method: 'POST',
        credentials: 'include',
      });
    } catch {
      // ignore
    }
    setUser(null);
    setYandex(null);
  };

  const submitConsent = async (granted: boolean) => {
    setConsentBusy(true);
    setError(null);
    try {
      const response = await fetch('/api/users/me/yandex-consent', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ consent: granted }),
      });
      if (!response.ok) {
        throw new Error(`Consent request failed: ${response.status}`);
      }
      const data: { consent: string; profile: YandexProfile } = await response.json();
      setYandex((prev) => ({
        yandex: true,
        consent: data.consent as YandexState['consent'],
        consentedAt: new Date().toISOString(),
        profile: prev?.profile ?? data.profile,
      }));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setConsentBusy(false);
    }
  };

  const downloadReport = async () => {
    if (!user?.authenticated) {
      setError('Not authenticated');
      return;
    }

    try {
      setLoading(true);
      setError(null);
      setReport(null);
      setReportError(null);

      const params = new URLSearchParams();
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      const qs = params.toString();

      const response = await fetch(`/api/reports${qs ? `?${qs}` : ''}`, {
        credentials: 'include',
      });

      if (response.status === 401) {
        setError('Session expired. Please log in again.');
        setUser(null);
        setYandex(null);
        return;
      }

      if (response.status === 400 || response.status === 404 || response.status === 503) {
        const body = await response.json().catch(() => null);
        const message =
          body?.error === 'period_not_processed'
            ? `Data is available up to ${body.available_until ?? '—'}. The requested period has not been processed by ETL yet.`
            : body?.error === 'customer_not_found'
              ? 'You are not registered as a customer in CRM, so there is no report for you.'
              : body?.message ?? `Request failed: ${response.status}`;
        setReportError(message);
        return;
      }

      if (!response.ok) {
        throw new Error(`Request failed: ${response.status}`);
      }

      const data: ReportData = await response.json();
      setReport(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
  };

  const saveReportAsJson = () => {
    if (!report) return;
    const blob = new Blob([JSON.stringify(report, null, 2)], {
      type: 'application/json',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `bionicpro-report-${report.period.from}_${report.period.to}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  if (!user) {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
        <div className="p-8 bg-white rounded-lg shadow-md text-center">
          <h1 className="text-2xl font-bold mb-4">BionicPRO Reports</h1>
          <p className="text-gray-600 mb-6">
            Please sign in to access your prosthetic usage reports.
          </p>
          <button
            onClick={handleLogin}
            className="px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600"
          >
            Login
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gray-100">
      <div className="p-8 bg-white rounded-lg shadow-md w-full max-w-4xl">
        <div className="flex items-center justify-between mb-6">
          <h1 className="text-2xl font-bold">Usage Reports</h1>
          <div className="flex items-center gap-4">
            <span className="text-sm text-gray-600">{user.username}</span>
            {yandex && (
              <span className="px-2 py-0.5 text-xs bg-indigo-100 text-indigo-700 rounded-full">
                Yandex ID
              </span>
            )}
            <button
              onClick={handleLogout}
              className="px-3 py-1 bg-gray-200 text-gray-700 rounded hover:bg-gray-300 text-sm"
            >
              Logout
            </button>
          </div>
        </div>

        <div className="flex flex-wrap items-end gap-3 mb-4">
          <div>
            <label className="block text-xs text-gray-500 mb-1" htmlFor="report-from">
              From
            </label>
            <input
              id="report-from"
              type="date"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm"
            />
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1" htmlFor="report-to">
              To
            </label>
            <input
              id="report-to"
              type="date"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              className="border border-gray-300 rounded px-2 py-1.5 text-sm"
            />
          </div>
          <button
            onClick={downloadReport}
            disabled={loading}
            className={`px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 ${
              loading ? 'opacity-50 cursor-not-allowed' : ''
            }`}
          >
            {loading ? 'Generating Report…' : 'Download Report'}
          </button>
          {!from && !to && (
            <span className="text-xs text-gray-400">
              empty period = the whole period processed by ETL
            </span>
          )}
        </div>

        {report && (
          <div className="mt-6">
            <div className="flex items-center justify-between">
              <div>
                <h2 className="font-semibold text-lg">
                  {report.customer.fullName} ({report.customer.email})
                </h2>
                <p className="text-xs text-gray-500">
                  period {report.period.from} … {report.period.to} · ETL data available until{' '}
                  {report.dataAvailableUntil}
                </p>
              </div>
              <button
                onClick={saveReportAsJson}
                className="px-3 py-1 bg-gray-200 text-gray-700 rounded hover:bg-gray-300 text-sm"
              >
                Save .json
              </button>
            </div>

            {report.prostheses.length === 0 && (
              <p className="mt-4 text-sm text-gray-600">
                No prostheses with telemetry data in the selected period.
              </p>
            )}

            {report.prostheses.map((p) => (
              <div key={p.prosthesisId} className="mt-6 border border-gray-200 rounded">
                <div className="px-4 py-2 bg-gray-50 border-b border-gray-200">
                  <span className="font-medium">{p.model}</span>{' '}
                  <span className="text-gray-500 text-sm">serial {p.serial}</span>
                </div>
                <dl className="grid grid-cols-2 sm:grid-cols-4 gap-x-4 gap-y-2 p-4 text-sm">
                  <div>
                    <dt className="text-xs text-gray-500">Days</dt>
                    <dd className="font-medium">{p.summary.days}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500">Avg battery, %</dt>
                    <dd className="font-medium">{p.summary.avgBatteryLevel.toFixed(1)}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500">Avg signal quality</dt>
                    <dd className="font-medium">{p.summary.avgSignalQuality.toFixed(1)}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500">Movements</dt>
                    <dd className="font-medium">{p.summary.totalMovements}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500">Gestures recognized</dt>
                    <dd className="font-medium">{p.summary.totalGestures}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500">Recognition error rate, %</dt>
                    <dd className="font-medium">
                      {(p.summary.recognitionErrorRate * 100).toFixed(1)}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500">Max temperature, °C</dt>
                    <dd className="font-medium">{p.summary.maxTemperatureC.toFixed(1)}</dd>
                  </div>
                  <div>
                    <dt className="text-xs text-gray-500">Error codes</dt>
                    <dd className="font-medium">
                      {p.summary.errorCodes.length > 0 ? p.summary.errorCodes.join(', ') : '—'}
                    </dd>
                  </div>
                </dl>
                <div className="overflow-x-auto px-4 pb-4">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="text-left text-gray-500 border-b border-gray-200">
                        <th className="py-1 pr-3">Day</th>
                        <th className="py-1 pr-3">Battery avg/min, %</th>
                        <th className="py-1 pr-3">Signal quality</th>
                        <th className="py-1 pr-3">Movements</th>
                        <th className="py-1 pr-3">Gestures</th>
                        <th className="py-1 pr-3">Errors</th>
                        <th className="py-1 pr-3">Temp max, °C</th>
                        <th className="py-1">Error codes</th>
                      </tr>
                    </thead>
                    <tbody>
                      {p.daily.map((d) => (
                        <tr key={d.day} className="border-b border-gray-100">
                          <td className="py-1 pr-3">{d.day}</td>
                          <td className="py-1 pr-3">
                            {d.avgBatteryLevel.toFixed(1)} / {d.minBatteryLevel.toFixed(1)}
                          </td>
                          <td className="py-1 pr-3">{d.avgSignalQuality.toFixed(1)}</td>
                          <td className="py-1 pr-3">{d.totalMovements}</td>
                          <td className="py-1 pr-3">{d.totalGestures}</td>
                          <td className="py-1 pr-3">{d.totalRecognitionErrors}</td>
                          <td className="py-1 pr-3">{d.maxTemperatureC.toFixed(1)}</td>
                          <td className="py-1">
                            {d.errorCodes.length > 0 ? d.errorCodes.join(', ') : '—'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        )}

        {reportError && (
          <div className="mt-4 p-4 bg-amber-100 text-amber-800 rounded text-sm">
            {reportError}
          </div>
        )}

        {yandex?.consent === 'GRANTED' && yandex.profile && (
          <div className="mt-6 p-4 bg-indigo-50 border border-indigo-200 rounded">
            <div className="flex items-center justify-between">
              <h2 className="font-semibold text-indigo-800">Yandex ID profile</h2>
              <span className="text-xs text-indigo-600">
                consent granted{yandex.consentedAt ? ` at ${new Date(yandex.consentedAt).toLocaleString()}` : ''}
              </span>
            </div>
            <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm text-gray-700">
              <div>Login: {yandex.profile.login || '—'}</div>
              <div>Email: {yandex.profile.email || '—'}</div>
              <div>Name: {yandex.profile.fullName || '—'}</div>
              <div>Account ID: {yandex.profile.accountId}</div>
            </dl>
            <p className="mt-2 text-xs text-gray-500">
              Profile data is stored by the BionicPRO user service (table yandex_user_profiles).
            </p>
          </div>
        )}

        {yandex?.consent === 'DENIED' && (
          <div className="mt-6 p-3 bg-gray-100 text-gray-600 rounded text-sm">
            You declined the use of your Yandex ID data — the profile is not stored by BionicPRO.
          </div>
        )}

        {error && (
          <div className="mt-4 p-4 bg-red-100 text-red-700 rounded">
            {error}
          </div>
        )}
      </div>

      {yandex?.consent === 'PENDING' && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg shadow-xl p-6 w-full max-w-md mx-4">
            <h2 className="text-xl font-bold mb-3">Yandex ID data consent</h2>
            <p className="text-gray-700 mb-2">
              You signed in with Yandex ID. BionicPRO asks for your permission to use and store
              your Yandex profile data:
            </p>
            <ul className="list-disc list-inside text-sm text-gray-600 mb-4">
              <li>Name: {yandex.profile.fullName || yandex.profile.login || 'unknown'}</li>
              <li>Email: {yandex.profile.email || yandex.profile.login || 'unknown'}</li>
            </ul>
            <div className="flex gap-3">
              <button
                onClick={() => submitConsent(true)}
                disabled={consentBusy}
                className="flex-1 px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 disabled:opacity-50"
              >
                Allow
              </button>
              <button
                onClick={() => submitConsent(false)}
                disabled={consentBusy}
                className="flex-1 px-4 py-2 bg-gray-200 text-gray-700 rounded hover:bg-gray-300 disabled:opacity-50"
              >
                Decline
              </button>
            </div>
            <p className="mt-3 text-xs text-gray-500">
              If you decline, the Yandex profile will not be stored and will not be used.
            </p>
          </div>
        </div>
      )}
    </div>
  );
};

export default ReportPage;
