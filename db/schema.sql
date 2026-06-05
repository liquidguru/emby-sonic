-- Emby Sonic — SQLite schema
-- Kept in sync with db/models.py (SQLAlchemy is the authoritative source for migrations)

CREATE TABLE tracks (
  id TEXT PRIMARY KEY,          -- Emby item ID
  title TEXT,
  artist TEXT,
  album TEXT,
  duration_ms INTEGER,
  file_path TEXT,
  analysed_at TIMESTAMP,
  analysis_version INTEGER
);

CREATE TABLE embeddings (
  track_id TEXT PRIMARY KEY REFERENCES tracks(id),
  vector BLOB,                  -- serialised 128-dim float32 array (numpy tobytes)
  tempo REAL,
  energy REAL,
  valence REAL,                 -- mood: sad → happy (Essentia)
  arousal REAL,                 -- mood: calm → energetic (Essentia)
  instrumentalness REAL,
  vocals_present INTEGER        -- boolean (0/1)
);

CREATE TABLE mixes (
  id TEXT PRIMARY KEY,
  name TEXT,
  created_at TIMESTAMP,
  cluster_id INTEGER
);

CREATE TABLE mix_tracks (
  mix_id TEXT REFERENCES mixes(id),
  track_id TEXT REFERENCES tracks(id),
  position INTEGER,
  PRIMARY KEY (mix_id, position)
);
