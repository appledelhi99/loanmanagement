#!/bin/sh
# ----------------------------------------------------------------------------
# Maven launcher for this repository ("only-script" Maven Wrapper style).
#
# It reads distributionUrl (and the optional distributionSha256Sum) from
# .mvn/wrapper/maven-wrapper.properties, installs that Maven version once into
# ${MAVEN_USER_HOME:-$HOME/.m2}/wrapper/dists/<version>, and then runs it with
# the arguments you pass. Everyone gets the same Maven version.
#
# Optional environment variables:
#   MVNW_REPOURL   base URL of a Maven repository mirror; replaces everything
#                  before /org/apache/maven/ in distributionUrl
#   MVNW_VERBOSE   true = print what the script is doing
#   JAVA_HOME      JDK used for the Java download fallback (when curl and wget are missing)
#
# This script is kept small on purpose. To switch to the official Apache Maven
# Wrapper scripts at any time, run:
#   mvn -N wrapper:wrapper -Dmaven=3.9.11 -Dtype=only-script
# ----------------------------------------------------------------------------

set -eu

verbose() {
  if [ "${MVNW_VERBOSE:-}" = "true" ]; then
    printf 'mvnw: %s\n' "$*"
  fi
}

die() {
  printf 'mvnw: %s\n' "$*" >&2
  exit 1
}

base_dir=$(cd "$(dirname "$0")" && pwd)
properties_file="$base_dir/.mvn/wrapper/maven-wrapper.properties"
[ -f "$properties_file" ] || die "cannot find $properties_file"

distribution_url=""
distribution_sha256=""
while IFS='=' read -r key value || [ -n "$key" ]; do
  value=$(printf '%s' "${value:-}" | tr -d '[:space:]')
  case "$key" in
    distributionUrl) distribution_url=$value ;;
    distributionSha256Sum) distribution_sha256=$value ;;
    *) ;;
  esac
done < "$properties_file"
[ -n "$distribution_url" ] || die "distributionUrl is missing in $properties_file"

if [ -n "${MVNW_REPOURL:-}" ]; then
  distribution_url="${MVNW_REPOURL%/}/org/apache/maven/${distribution_url#*/org/apache/maven/}"
fi

archive_name=${distribution_url##*/}
case "$archive_name" in
  apache-maven-*-bin.zip) ;;
  *) die "distributionUrl must end with apache-maven-<version>-bin.zip, found: $archive_name" ;;
esac
dist_name=${archive_name%-bin.zip}

maven_user_home=${MAVEN_USER_HOME:-$HOME/.m2}
maven_home="$maven_user_home/wrapper/dists/$dist_name"

java_command() {
  if [ -n "${JAVA_HOME:-}" ]; then
    printf '%s\n' "$JAVA_HOME/bin/java"
  else
    command -v java || true
  fi
}

download() {
  url=$1
  target=$2
  if command -v curl >/dev/null 2>&1; then
    verbose "downloading with curl: $url"
    curl --fail --silent --show-error --location --output "$target" "$url"
  elif command -v wget >/dev/null 2>&1; then
    verbose "downloading with wget: $url"
    wget --quiet --output-document="$target" "$url"
  else
    java_bin=$(java_command)
    [ -n "$java_bin" ] && [ -x "$java_bin" ] || die "need curl, wget or a JDK to download Maven"
    verbose "downloading with Java: $url"
    downloader="${target%/*}/Downloader.java"
    cat > "$downloader" <<'JAVA'
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

public class Downloader {
    public static void main(String[] args) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(args[0])).GET().build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(Path.of(args[1])));
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + args[0]);
        }
    }
}
JAVA
    "$java_bin" "$downloader" "$url" "$target"
  fi
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d ' ' -f 1
  else
    die "distributionSha256Sum is set, but neither sha256sum nor shasum is available"
  fi
}

if [ ! -x "$maven_home/bin/mvn" ]; then
  verbose "installing $dist_name into $maven_home"
  tmp_dir=$(mktemp -d 2>/dev/null) || die "cannot create a temporary directory"
  trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

  # Use the .zip when unzip exists; otherwise use the .tar.gz that Maven publishes next to it.
  if command -v unzip >/dev/null 2>&1; then
    download_url=$distribution_url
    archive="$tmp_dir/$dist_name-bin.zip"
  else
    [ -z "$distribution_sha256" ] || die "distributionSha256Sum refers to the .zip, but unzip is not installed"
    download_url="${distribution_url%.zip}.tar.gz"
    archive="$tmp_dir/$dist_name-bin.tar.gz"
  fi

  download "$download_url" "$archive" || die "download failed: $download_url"

  if [ -n "$distribution_sha256" ]; then
    actual_sha256=$(sha256_of "$archive")
    [ "$actual_sha256" = "$distribution_sha256" ] || die "checksum mismatch for $archive_name"
  fi

  case "$archive" in
    *.zip) unzip -q "$archive" -d "$tmp_dir" || die "cannot unzip $archive" ;;
    *) tar -xzf "$archive" -C "$tmp_dir" || die "cannot extract $archive" ;;
  esac
  [ -x "$tmp_dir/$dist_name/bin/mvn" ] || die "the archive does not contain $dist_name/bin/mvn"

  mkdir -p "${maven_home%/*}"
  # A parallel build may have installed the same version meanwhile; keep the first one.
  if [ ! -d "$maven_home" ]; then
    mv "$tmp_dir/$dist_name" "$maven_home" || [ -x "$maven_home/bin/mvn" ] || die "cannot install Maven into $maven_home"
  fi
  rm -rf "$tmp_dir"
  trap - EXIT HUP INT TERM
fi

unset MVNW_REPOURL MVNW_VERBOSE
exec "$maven_home/bin/mvn" "$@"
