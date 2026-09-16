# Installed icon-pack compatibility

Supported: legacy icon-pack discovery intents declared in the manifest, static
`appfilter.xml` component mappings and `drawable.xml` icon catalogs, in `res/xml`
or assets. Relative activity classes are expanded; one activity's mapping is never
invented as a package-wide fallback. Duplicate mappings use the first entry.

All parsed catalog entries are searchable and selectable, with recycled asynchronous
previews. There is no 100/400-icon UI truncation. Reset does not require the previous
pack to remain installed. Missing resources fall back to the ordinary app icon.
Package, appearance and selection changes invalidate the renderer caches.

Bounds: 4 MiB per asset XML, 50,000 items and 200,000 parser events per XML, depth 8,
32 attributes per element, bounded ASCII resource names, no DOCTYPE. Oversized or
malformed catalogs fail closed, rather than silently selecting from a truncated list.
Raster resources are bounds-checked and sampled; previews are rasterized to at most
256×256 and held in an 8 MiB cache. Resource decoding runs off the UI thread in the picker.
XML drawables cannot be pre-sized, so inflation exhaustion is caught, the preview cache is
dropped, and rendering falls back to the ordinary app icon rather than crashing HOME.

Not implemented: masks/background composition, dynamic calendar dates or clock hands.
These are not required for this release; static mappings and normal icon fallback
remain available. No executable code or artwork is imported from a pack into Cenix.

`IconPackXmlTest` covers activity mappings, catalogs beyond the former limit, search,
malformed XML, excessive depth and DOCTYPE rejection. Representative real-pack/device
visual compatibility and hostile drawable-resource stress testing remain unqualified.
