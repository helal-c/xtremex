CREATE TABLE IF NOT EXISTS accounts (
  id uuid PRIMARY KEY,
  user_id varchar(32) UNIQUE NOT NULL CHECK(user_id ~ '^[a-z0-9_.-]{3,32}$'),
  public_key text,
  fingerprint char(64) UNIQUE,
  status text NOT NULL DEFAULT 'pending' CHECK(status IN ('pending','approved','blocked')),
  generation integer NOT NULL DEFAULT 1,
  device_type text NOT NULL DEFAULT 'unknown',
  device_model varchar(128) NOT NULL DEFAULT '',
  app_version varchar(40),
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen timestamptz,
  was_playing boolean NOT NULL DEFAULT false,
  CHECK ((public_key IS NULL) = (fingerprint IS NULL))
);
CREATE TABLE IF NOT EXISTS challenges (
  id uuid PRIMARY KEY, nonce text NOT NULL, action text NOT NULL,
  user_id varchar(32) NOT NULL, fingerprint char(64) NOT NULL,
  expires_at timestamptz NOT NULL, consumed_at timestamptz
);
CREATE INDEX IF NOT EXISTS challenges_expiry ON challenges(expires_at);
CREATE TABLE IF NOT EXISTS sessions (
  token_hash char(64) PRIMARY KEY, account_id uuid NOT NULL REFERENCES accounts(id),
  generation integer NOT NULL, expires_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_account ON sessions(account_id);
CREATE TABLE IF NOT EXISTS daily_activity (
  account_id uuid NOT NULL REFERENCES accounts(id), day date NOT NULL,
  watch_seconds double precision NOT NULL DEFAULT 0 CHECK(watch_seconds >= 0),
  PRIMARY KEY(account_id,day)
);
CREATE TABLE IF NOT EXISTS rate_limits (
  bucket text PRIMARY KEY, window_start timestamptz NOT NULL, count integer NOT NULL
);
CREATE TABLE IF NOT EXISTS admin_sessions (
  token_hash char(64) PRIMARY KEY, csrf_hash char(64) NOT NULL,
  expires_at timestamptz NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS audit (
  id bigserial PRIMARY KEY, actor text NOT NULL,
  action text NOT NULL, user_id varchar(32), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS settings (key text PRIMARY KEY, value text NOT NULL);
INSERT INTO settings(key,value) VALUES('support_number','') ON CONFLICT DO NOTHING;
