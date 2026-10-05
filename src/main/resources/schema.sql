CREATE TABLE IF NOT EXISTS notifications (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL,
    notification_source TEXT NOT NULL,
    received_on TEXT NOT NULL,
    metadata_map TEXT NOT NULL,
    external_links TEXT NOT NULL DEFAULT '{}',
    unread INTEGER NOT NULL DEFAULT 1 CHECK (unread IN (0, 1))
);

CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    links TEXT NOT NULL,
    metadata TEXT NOT NULL,
    steps TEXT NOT NULL DEFAULT '[]',
    status TEXT NOT NULL DEFAULT 'PENDING',
    notify_me_on TEXT
);
