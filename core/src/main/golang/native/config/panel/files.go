package panel

// ProfileFiles — tun.json и inbound.json, выведенные разбором конфига.
type ProfileFiles struct {
	Tun     TunPrefs
	Inbound InboundPrefs
}

// Write — оба файла в каталог профиля; первый сбой возвращается
func (f ProfileFiles) Write(dir string) error {
	if err := WriteTunPrefs(dir, f.Tun); err != nil {
		return err
	}

	return WriteInboundPrefs(dir, f.Inbound)
}
