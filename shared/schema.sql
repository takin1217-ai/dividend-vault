CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS symbols (
  symbol TEXT PRIMARY KEY,
  market TEXT NOT NULL,
  name TEXT,
  instrument TEXT NOT NULL,
  exchange TEXT,
  currency TEXT,
  yield_frac REAL,
  market_cap REAL,
  avg_volume REAL,
  selected INTEGER NOT NULL DEFAULT 0,
  pool_rank INTEGER,
  listed_date TEXT,
  select_note TEXT,
  updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_symbols_market ON symbols(market, selected, pool_rank);

CREATE TABLE IF NOT EXISTS bars (
  symbol TEXT NOT NULL,
  date TEXT NOT NULL,
  open REAL,
  high REAL,
  low REAL,
  close REAL,
  adj_close REAL,
  volume REAL,
  source TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (symbol, date)
);

CREATE TABLE IF NOT EXISTS bar_revisions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  symbol TEXT NOT NULL,
  date TEXT NOT NULL,
  field TEXT NOT NULL,
  old_value REAL,
  new_value REAL,
  revised_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dividends (
  symbol TEXT NOT NULL,
  ex_date TEXT NOT NULL,
  amount REAL NOT NULL,
  source TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (symbol, ex_date)
);

CREATE TABLE IF NOT EXISTS dividend_revisions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  symbol TEXT NOT NULL,
  ex_date TEXT NOT NULL,
  old_amount REAL,
  new_amount REAL,
  revised_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS splits (
  symbol TEXT NOT NULL,
  ex_date TEXT NOT NULL,
  numerator REAL,
  denominator REAL,
  source TEXT NOT NULL,
  PRIMARY KEY (symbol, ex_date)
);

CREATE TABLE IF NOT EXISTS sync_state (
  symbol TEXT PRIMARY KEY,
  first_date TEXT,
  last_date TEXT,
  bar_count INTEGER NOT NULL DEFAULT 0,
  div_count INTEGER NOT NULL DEFAULT 0,
  last_attempt TEXT,
  last_success TEXT,
  last_error TEXT,
  status TEXT NOT NULL DEFAULT 'empty'
);

CREATE TABLE IF NOT EXISTS sync_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  started_at TEXT NOT NULL,
  finished_at TEXT,
  markets TEXT,
  requested INTEGER NOT NULL DEFAULT 0,
  succeeded INTEGER NOT NULL DEFAULT 0,
  failed INTEGER NOT NULL DEFAULT 0,
  bars_written INTEGER NOT NULL DEFAULT 0,
  note TEXT
);

CREATE TABLE IF NOT EXISTS backtests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at TEXT NOT NULL,
  market TEXT NOT NULL,
  strategy TEXT NOT NULL,
  params_json TEXT NOT NULL,
  metrics_json TEXT NOT NULL,
  baseline_json TEXT,
  equity_json TEXT NOT NULL,
  holdings_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS paper_accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  market TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  currency TEXT NOT NULL,
  cash REAL NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS paper_positions (
  account_id INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  qty REAL NOT NULL,
  avg_price REAL NOT NULL,
  PRIMARY KEY (account_id, symbol)
);

CREATE TABLE IF NOT EXISTS paper_orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id INTEGER NOT NULL,
  symbol TEXT NOT NULL,
  side TEXT NOT NULL,
  qty REAL NOT NULL,
  status TEXT NOT NULL,
  reason TEXT,
  created_at TEXT NOT NULL,
  filled_at TEXT,
  fill_price REAL,
  fee REAL NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS paper_equity (
  account_id INTEGER NOT NULL,
  date TEXT NOT NULL,
  equity REAL NOT NULL,
  cash REAL NOT NULL,
  PRIMARY KEY (account_id, date)
);

CREATE TABLE IF NOT EXISTS kis_orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at TEXT NOT NULL,
  account_alias TEXT NOT NULL,
  mode TEXT NOT NULL,
  market TEXT NOT NULL,
  symbol TEXT NOT NULL,
  side TEXT NOT NULL,
  qty REAL NOT NULL,
  price REAL,
  status TEXT NOT NULL,
  request_json TEXT NOT NULL,
  response_json TEXT
);
