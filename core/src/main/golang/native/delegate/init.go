package delegate

import (
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"syscall"

	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/log"

	"cfa/native/app"
	"cfa/native/platform"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/constant"
)

var errBlocked = errors.New("blocked")

func Init(home, versionName, gitVersion string, platformVersion int) {
	log.Infoln("Init core, home: %s, versionName: %s, gitVersion: %s, platformVersion: %d", home, versionName, gitVersion, platformVersion)
	constant.SetHomeDir(home)
	if versions := strings.Split(gitVersion, "_"); len(versions) == 3 {
		constant.Version = fmt.Sprintf("%s-%s-CMFA-%s", strings.ToLower(versions[0]), versions[1], strings.ToLower(versionName))
		constant.BuildTime = versions[2]
	} else {
		constant.Version = gitVersion
	}
	constant.Version = strings.ToLower(constant.Version)
	app.ApplyVersionName(versionName)
	app.ApplyPlatformVersion(platformVersion)

	process.DefaultPackageNameResolver = func(metadata *constant.Metadata) (string, error) {
		src, dst := socketPair(metadata)

		if src == nil || dst == nil {
			return "", process.ErrInvalidNetwork
		}

		uid := app.QuerySocketUid(src, dst)
		pkg := app.QueryAppByUid(uid)

		log.Debugln("[PKG] %s --> %s by %d[%s]", metadata.SourceAddress(), metadata.RemoteAddress(), uid, pkg)

		return pkg, nil
	}

	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		if platform.ShouldBlockConnection() {
			return errBlocked
		}

		return conn.Control(func(fd uintptr) {
			app.MarkSocket(int(fd))
		})
	}
}

// socketPair возвращает пару адресов, по которой ищется владелец соединения:
// сокет приложения и та сторона, с которой оно разговаривает.
//
// Обычно её кладёт сам вход: TUN, UDP и HTTP заполняют RawSrcAddr/RawDstAddr.
// SOCKS-вход этого не делает — NewSocket кладёт только SrcIP/SrcPort и
// InIP/InPort, — поэтому раньше всё, что приходило на локальный порт по SOCKS,
// уходило с ErrInvalidNetwork, даже не дойдя до опроса, и оставалось в журнале
// и в списке соединений без имени приложения. Собираем ту же пару из полей,
// которые заполнены всегда: сокет приложения и адрес нашего слушателя —
// ровно то, что для этого случая передаёт HTTP-вход.
func socketPair(metadata *constant.Metadata) (net.Addr, net.Addr) {
	if metadata.RawSrcAddr != nil && metadata.RawDstAddr != nil {
		return metadata.RawSrcAddr, metadata.RawDstAddr
	}

	if !metadata.SrcIP.IsValid() || metadata.SrcPort == 0 {
		return nil, nil
	}

	if !metadata.InIP.IsValid() || metadata.InPort == 0 {
		return nil, nil
	}

	src := netip.AddrPortFrom(metadata.SrcIP, metadata.SrcPort)
	in := netip.AddrPortFrom(metadata.InIP, metadata.InPort)

	switch metadata.NetWork {
	case constant.TCP:
		return net.TCPAddrFromAddrPort(src), net.TCPAddrFromAddrPort(in)
	case constant.UDP:
		return net.UDPAddrFromAddrPort(src), net.UDPAddrFromAddrPort(in)
	}

	return nil, nil
}
