/**
 * Database access: a HikariCP pool and one thin DAO per table.
 *
 * <p>No ORM by design (docs/Design Decisions.md) — every statement is visible SQL, which is the
 * point of a project whose thesis is the database itself.
 */
package com.achen.shelf.db;
