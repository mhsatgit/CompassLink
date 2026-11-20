function Get-AndroidCommandLineArchive([xml]$Feed) {
    # Use XML nodes directly: PowerShell adapts a plain <checksum> to a string,
    # while <checksum type="sha1"> is an element. '#text' works only for the latter.
    # local-name() also supports Google's namespace prefixes and schema revisions.
    $package = $Feed.SelectSingleNode("/*[local-name()='sdk-repository']/*[local-name()='remotePackage' and @path='cmdline-tools;latest']")
    if (!$package) { throw 'Google SDK feed has no cmdline-tools;latest package.' }
    $archive = $package.SelectSingleNode("*[local-name()='archives']/*[local-name()='archive'][*[local-name()='host-os' and normalize-space(.)='windows']]/*[local-name()='complete']")
    if (!$archive) { throw 'Google SDK feed has no complete Windows command-line tools archive.' }
    $urlNode = $archive.SelectSingleNode("*[local-name()='url']")
    $checksumNode = $archive.SelectSingleNode("*[local-name()='checksum']")
    if (!$urlNode -or !$checksumNode) { throw 'Windows command-line tools archive is missing its URL or checksum.' }
    $name = $urlNode.InnerText.Trim()
    if ($name -notmatch '^commandlinetools-win-[a-zA-Z0-9_.-]+\.zip$') {
        throw "Unexpected Windows command-line tools archive name: $name"
    }
    $algorithm = $checksumNode.GetAttribute('type').Replace('-', '').ToUpperInvariant()
    # The repository2-1 schema uses SHA-1 without a type attribute.
    if (!$algorithm) { $algorithm = 'SHA1' }
    $checksum = $checksumNode.InnerText.Trim()
    if (!(($algorithm -eq 'SHA1' -and $checksum -match '^[a-fA-F0-9]{40}$') -or
        ($algorithm -eq 'SHA256' -and $checksum -match '^[a-fA-F0-9]{64}$'))) {
        throw "Invalid or unsupported Windows command-line tools checksum ($algorithm)."
    }
    return @{
        Name = $name
        Url = 'https://dl.google.com/android/repository/' + $name
        Checksum = $checksum
        Algorithm = $algorithm
    }
}
