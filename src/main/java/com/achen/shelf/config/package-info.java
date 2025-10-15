/**
 * Loading and validating category configuration.
 *
 * <p>{@link com.achen.shelf.config.CategoryConfigLoader} reads {@code categories/<name>.yaml} into
 * {@link com.achen.shelf.config.CategoryConfig} and rejects anything malformed up front; {@link
 * com.achen.shelf.config.SpecValidator} enforces a category's spec schema against both hand-written
 * seeds and retailer-supplied values.
 */
package com.achen.shelf.config;
