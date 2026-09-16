package config

import (
	"github.com/metacubex/mihomo/component/geodata"
	_ "github.com/metacubex/mihomo/component/geodata/standard"
	"github.com/metacubex/mihomo/component/mmdb"
	"github.com/metacubex/mihomo/constant"
)

// The core opens geoip/ASN once per process and keeps geosite matchers in a
// cache, so files replaced on disk are invisible to it until these are reset.
// A reset never closes the readers in use (the old mapping stays valid for the
// running tunnel, at the price of one leaked mapping per update); the next
// lookup or config load opens the new files. A file that fails to open is left
// alone: the reader would abort the process, and a broken geosite would fail
// every domain lookup.
func ReloadGeoData() {
	if mmdb.Verify(constant.Path.MMDB()) {
		mmdb.ReloadIP()
	}

	if mmdb.Verify(constant.Path.ASN()) {
		mmdb.ReloadASN()
	}

	geodata.ClearGeoIPCache()

	if geositeReadable() {
		geodata.ClearGeoSiteCache()
	}
}

// geodata.Verify would answer from the very cache being replaced, so the new
// file is parsed through a fresh loader instead.
func geositeReadable() bool {
	loader, err := geodata.GetGeoDataLoader("standard")
	if err != nil {
		return false
	}

	_, err = loader.LoadGeoSite("cn")

	return err == nil
}
