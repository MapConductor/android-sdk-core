# US antenna horizons

Source: FCC ASR weekly complete registration export, downloaded 2026-11-10.
https://data.fcc.gov/download/pub/uls/complete/r_tower.zip

This is a snapshot of registered antenna structures, not a cellular base station database. FCC registration does not establish that a transmitter is operating. Only current, constructed registrations with coordinates and a positive overall height above ground are retained. Owner names, addresses and contacts are discarded. Data provenance, filtering, SHA-256 and binary layout are in public/antennas/us-antennas.json.

The radius is a **geometric horizon model**, not measured or predicted radio coverage. It assumes a spherical Earth with R=6378137 m and a receiver 1.5 m above the ground. Radius in metres is R*(acos(R/(R+h))+acos(R/(R+1.5))). It ignores terrain, antenna elevation within the structure, radiation pattern, obstructions, frequency and atmospheric refraction. geodesic=true traces that surface distance on the sphere. The false toggle is a local-plane comparison.

Low (<60 m), medium (60–150 m) and tall (>=150 m) structures are colored blue, green and orange. Alaska and US overview camera presets demonstrate latitude-dependent projection. Each platform bundles the same ~2.27 MB file; no network or token is needed to load the sample data. Source is factual FCC public-access data, with source credit shown on the sample.

Rebuild: download r_tower.zip, then `python3 pack.py r_tower.zip us-antennas.bin`. Copy the .bin and .json to each example's antennas asset directory. MCCA header and count are little-endian; each 16-byte record stores float32 latitude, float32 longitude, float32 height_m and uint32 registration.
