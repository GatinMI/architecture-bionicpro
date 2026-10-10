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

const ReportPage: React.FC = () => {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [yandex, setYandex] = useState<YandexState | null>(null);
  const [consentBusy, setConsentBusy] = useState(false);

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

      const response = await fetch('/api/reports', {
        credentials: 'include',
      });

      if (response.status === 401) {
        setError('Session expired. Please log in again.');
        setUser(null);
        setYandex(null);
        return;
      }

      if (!response.ok) {
        throw new Error(`Request failed: ${response.status}`);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred');
    } finally {
      setLoading(false);
    }
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
      <div className="p-8 bg-white rounded-lg shadow-md w-full max-w-2xl">
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

        <button
          onClick={downloadReport}
          disabled={loading}
          className={`px-4 py-2 bg-blue-500 text-white rounded hover:bg-blue-600 ${
            loading ? 'opacity-50 cursor-not-allowed' : ''
          }`}
        >
          {loading ? 'Generating Report...' : 'Download Report'}
        </button>

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
              <li>Email: {yandex.profile.email || 'unknown'}</li>
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
