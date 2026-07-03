-- The database layout reference-app expects. Databases, roles, and ALL access
-- permissions are provisioned WITH the database, outside the application — this script
-- documents that layout and creates it for the local compose PostgreSQL and the
-- integration tests. Passwords are local/test-only placeholders; real environments get
-- generated credentials from a secret store.

-- Group roles: NOLOGIN; permissions attach to these, never to login users directly.
CREATE ROLE reference_owner NOLOGIN;
CREATE ROLE reference_rw NOLOGIN;
CREATE ROLE reference_ro NOLOGIN;

-- The application schema, owned by the owner group; rw may create objects in it.
CREATE SCHEMA reference AUTHORIZATION reference_owner;
GRANT CREATE, USAGE ON SCHEMA reference TO reference_rw;
GRANT USAGE ON SCHEMA reference TO reference_ro;

-- Per-application login users: group membership only, no direct grants.
CREATE ROLE reference_app_rw_user LOGIN PASSWORD 'rw-local' IN ROLE reference_rw;
CREATE ROLE reference_app_ro_user LOGIN PASSWORD 'ro-local' IN ROLE reference_ro;

-- Default privileges: every object the migration-running rw user creates is
-- automatically granted to the rw/ro groups — which is why the Liquibase changelog
-- contains NO GRANT statements.
ALTER DEFAULT PRIVILEGES FOR ROLE reference_app_rw_user IN SCHEMA reference
    GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLES TO reference_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE reference_app_rw_user IN SCHEMA reference
    GRANT SELECT ON TABLES TO reference_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE reference_app_rw_user IN SCHEMA reference
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO reference_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE reference_app_rw_user IN SCHEMA reference
    GRANT SELECT ON SEQUENCES TO reference_ro;
