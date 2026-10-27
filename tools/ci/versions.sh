# The pinned versions of the CI tools (sourced by common.sh). The cache keys in
# .github/workflows/ci.yml hash this file, so an edit here refreshes them.

IDF_VERSION=v5.5.1
# The component manager that wrote firmware/dependencies.lock. Another version
# can read the lock differently: 2.3.0 (in espressif/idf:v5.5.1) dropped it and
# resolved LVGL 9.6 although main/idf_component.yml asks for 9.4.
COMPONENT_MANAGER_VERSION=2.4.9
