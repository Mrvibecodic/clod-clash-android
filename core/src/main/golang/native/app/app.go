package app

import (
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

var appVersionName string
var platformVersion int
var installedAppsUid atomic.Pointer[map[int]string]

func ApplyVersionName(versionName string) {
	appVersionName = versionName
}

func ApplyPlatformVersion(version int) {
	platformVersion = version
}

func VersionName() string {
	return appVersionName
}

func PlatformVersion() int {
	return platformVersion
}

func NotifyInstallAppsChanged(uidList string) {
	uids := map[int]string{}

	for _, item := range strings.Split(uidList, ",") {
		kv := strings.Split(item, ":")
		if len(kv) == 2 {
			uid, err := strconv.Atoi(kv[0])
			if err != nil {
				continue
			}

			uids[uid] = kv[1]
		}
	}

	installedAppsUid.Store(&uids)
}

func QueryAppByUid(uid int) string {
	uids := installedAppsUid.Load()
	if uids == nil {
		return ""
	}

	return (*uids)[uid]
}

func NotifyTimeZoneChanged(name string, offset int) {
	time.Local = time.FixedZone(name, offset)
}
