package redact

import "strings"

const trailing = `.,;:!?)]}"'`

const mask = "/***"

func Text(text string) string {
	if text == "" || !strings.Contains(text, "://") {
		return text
	}

	var builder strings.Builder

	index := 0

	for index < len(text) {
		separator := strings.Index(text[index:], "://")
		if separator < 0 {
			builder.WriteString(text[index:])

			return builder.String()
		}

		separator += index

		start := separator
		for start > index && isSchemeByte(text[start-1]) {
			start--
		}

		scheme := strings.ToLower(text[start:separator])

		if scheme != "http" && scheme != "https" {
			builder.WriteString(text[index : separator+3])

			index = separator + 3

			continue
		}

		end := separator + 3
		for end < len(text) && !isDelimiterByte(text[end]) {
			end++
		}

		stop := end
		for stop > separator+3 && strings.IndexByte(trailing, text[stop-1]) >= 0 {
			stop--
		}

		builder.WriteString(text[index:start])
		builder.WriteString(text[start : separator+3])
		builder.WriteString(maskBody(text[separator+3 : stop]))
		builder.WriteString(text[stop:end])

		index = end
	}

	return builder.String()
}

func maskBody(body string) string {
	authority := body

	if cut := strings.IndexAny(authority, "/?#"); cut >= 0 {
		authority = authority[:cut]
	}

	host := authority
	if at := strings.LastIndexByte(authority, '@'); at >= 0 {
		host = authority[at+1:]
	}

	remainder := body[len(authority):]
	hasPath := strings.HasPrefix(remainder, "/") && strings.Trim(remainder, "/") != ""
	hidden := host != authority ||
		hasPath ||
		strings.ContainsAny(remainder, "?#")

	if !hidden {
		return body
	}

	if hasPath {
		return host + mask
	}

	return host
}

func isSchemeByte(value byte) bool {
	switch {
	case value >= 'a' && value <= 'z':
		return true
	case value >= 'A' && value <= 'Z':
		return true
	case value >= '0' && value <= '9':
		return true
	case value == '+' || value == '-' || value == '.':
		return true
	}

	return false
}

func isDelimiterByte(value byte) bool {
	switch value {
	case ' ', '\t', '\n', '\r', '\v', '\f', '"', '\'', '<', '>', '\\', '`':
		return true
	}

	return false
}
