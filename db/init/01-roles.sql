-- The database layout reference-app expects. Databases, roles, and ALL access
-- permissions are provisioned WITH the database, outside the application — this script
-- documents that layout and creates it for the local compose PostgreSQL and the
-- integration tests. Passwords are local/test-only placeholders; real environments get
-- generated credentials from a secret store.

-- Group roles: NOLOGIN; permissions attach to these, never to login users directly.
-- reference_owner owns the schema AND every object in it, so ownership never depends on a
-- login user — logins can be rotated or issued dynamically without orphaning objects.
CREATE ROLE reference_owner NOLOGIN;
CREATE ROLE reference_rw NOLOGIN;
CREATE ROLE reference_ro NOLOGIN;

-- The application schema. Only the owner may create objects in it.
CREATE SCHEMA reference AUTHORIZATION reference_owner;
GRANT USAGE ON SCHEMA reference TO reference_rw, reference_ro;

-- Migration login (Liquibase): a member of the owner role whose sessions run AS the owner
-- role, so every object it creates is owned by reference_owner.
CREATE ROLE reference_app_migrate_user LOGIN PASSWORD 'migrate-local' IN ROLE reference_owner;
ALTER ROLE reference_app_migrate_user SET role = 'reference_owner';

-- Serving logins: data access through group membership only, no schema rights.
CREATE ROLE reference_app_rw_user LOGIN PASSWORD 'rw-local' IN ROLE reference_rw;
CREATE ROLE reference_app_ro_user LOGIN PASSWORD 'ro-local' IN ROLE reference_ro;

-- Default privileges for objects the owner role creates: the changelog needs NO GRANT
-- statements. rw gets DML only — no TRUNCATE, REFERENCES or TRIGGER, which are bulk-
-- destructive or schema-shaped; sequences need USAGE for nextval, not UPDATE (setval).
ALTER DEFAULT PRIVILEGES FOR ROLE reference_owner IN SCHEMA reference
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO reference_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE reference_owner IN SCHEMA reference
    GRANT SELECT ON TABLES TO reference_ro;
ALTER DEFAULT PRIVILEGES FOR ROLE reference_owner IN SCHEMA reference
    GRANT USAGE, SELECT ON SEQUENCES TO reference_rw;
ALTER DEFAULT PRIVILEGES FOR ROLE reference_owner IN SCHEMA reference
    GRANT SELECT ON SEQUENCES TO reference_ro;
