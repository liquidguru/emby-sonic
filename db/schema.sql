-- Emby Sonic — SQLite schema
-- Kept in sync with db/models.py (SQLAlchemy is the authoritative source for migrations)

CREATE TABLE tracks (
  id TEXT PRIMARY KEY,          -- Emby item ID
  title TEXT,
  artist TEXT,
  album TEXT,
  genre TEXT,                  -- primary Emby genre used for mix naming
  duration_ms INTEGER,
  file_path TEXT,
  analysed_at TIMESTAMP,
  analysis_version INTEGER,
  analysis_status TEXT DEFAULT 'pending',  -- pending | done | error (crash-safe resume)
  claimed_at TIMESTAMP,         -- worker lease; NULL or stale = reclaimable
  error TEXT                    -- last failure message, if status='error'
);

CREATE TABLE embeddings (
  track_id TEXT PRIMARY KEY REFERENCES tracks(id),
  vector BLOB,                  -- serialised 128-dim float32 array (FAISS-indexed; rebuilt from DB)
  raw_vector BLOB,              -- native 2048-dim CNN14 vector (for PCA refit / index rebuild)
  tempo REAL,
  energy REAL,
  valence REAL,                 -- mood: sad → happy (Essentia)
  arousal REAL,                 -- mood: calm → energetic (Essentia)
  instrumentalness REAL,
  vocals_present INTEGER,        -- boolean (0/1)
  lufs REAL,                    -- integrated loudness (EBU R128 LUFS); drives volume normalisation
  effective_start_ms INTEGER,   -- where audible music starts; NULL = unmeasured (crossfade trimming)
  effective_end_ms INTEGER      -- where audible music ends; NULL = unmeasured (crossfade trimming)
);

CREATE TABLE mixes (
  id TEXT PRIMARY KEY,
  name TEXT,
  created_at TIMESTAMP,
  cluster_id INTEGER,
  centroid BLOB                -- serialised k-means centroid
);

CREATE TABLE mix_tracks (
  mix_id TEXT REFERENCES mixes(id),
  track_id TEXT REFERENCES tracks(id),
  position INTEGER,
  PRIMARY KEY (mix_id, position)
);

CREATE TABLE schema_migrations (
  version INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  applied_at TEXT NOT NULL
);
