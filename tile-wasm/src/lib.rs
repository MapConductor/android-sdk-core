use std::f64::consts::PI;

const MAX_MERCATOR_LAT: f64 = 85.05112878;

// Static buffers — safe because Wasm is single-threaded (Chicory enforces this).
static mut POINTS_LAT: Vec<f64> = Vec::new();
static mut POINTS_LON: Vec<f64> = Vec::new();

static mut RESULT_INDICES: Vec<i32> = Vec::new();
static mut RESULT_NORM_X: Vec<f64> = Vec::new();
static mut RESULT_NORM_Y: Vec<f64> = Vec::new();

// ----- Memory management -----

#[no_mangle]
pub extern "C" fn wasm_alloc(size: i32) -> i32 {
    let layout = std::alloc::Layout::from_size_align(size as usize, 8).unwrap();
    unsafe { std::alloc::alloc(layout) as i32 }
}

#[no_mangle]
pub extern "C" fn wasm_dealloc(ptr: i32, size: i32) {
    let layout = std::alloc::Layout::from_size_align(size as usize, 8).unwrap();
    unsafe { std::alloc::dealloc(ptr as *mut u8, layout) }
}

// ----- Index management -----

/// Loads N marker positions into the flat spatial index.
/// Must be called (once) before any query_and_transform call.
///
/// lats_ptr / lons_ptr: pointers into Wasm linear memory (f64[count]).
#[no_mangle]
pub extern "C" fn build_index(lats_ptr: i32, lons_ptr: i32, count: i32) {
    let n = count as usize;
    unsafe {
        let lats = std::slice::from_raw_parts(lats_ptr as *const f64, n);
        let lons = std::slice::from_raw_parts(lons_ptr as *const f64, n);
        POINTS_LAT.clear();
        POINTS_LAT.extend_from_slice(lats);
        POINTS_LON.clear();
        POINTS_LON.extend_from_slice(lons);
    }
}

// ----- Combined spatial query + coordinate transform -----

/// Finds all indexed points within the axis-aligned bounding box
/// [min_lat, max_lat] × [min_lon, max_lon] and simultaneously computes
/// each point's normalized tile-space position (tilePoint − tileOrigin).
///
/// Parameters:
///   min_lat / max_lat / min_lon / max_lon — padded tile bounds in WGS-84 degrees
///   tile_x / tile_y   — integer tile indices (origin corner in tile-space)
///   zoom_n            — 2^zoom (tiles per axis)
///
/// Returns the number of matching points.
/// Call get_result_{indices,norm_x,norm_y}_ptr() to read results.
#[no_mangle]
pub extern "C" fn query_and_transform(
    min_lat: f64,
    max_lat: f64,
    min_lon: f64,
    max_lon: f64,
    tile_x: f64,
    tile_y: f64,
    zoom_n: f64,
) -> i32 {
    unsafe {
        RESULT_INDICES.clear();
        RESULT_NORM_X.clear();
        RESULT_NORM_Y.clear();

        for i in 0..POINTS_LAT.len() {
            let lat = POINTS_LAT[i];
            let lon = POINTS_LON[i];
            if lat < min_lat || lat > max_lat || lon < min_lon || lon > max_lon {
                continue;
            }
            let (tx, ty) = geo_to_tile_point(lon, lat, zoom_n);
            RESULT_INDICES.push(i as i32);
            RESULT_NORM_X.push(tx - tile_x);
            RESULT_NORM_Y.push(ty - tile_y);
        }
        RESULT_INDICES.len() as i32
    }
}

#[no_mangle]
pub extern "C" fn get_result_indices_ptr() -> i32 {
    unsafe { RESULT_INDICES.as_ptr() as i32 }
}

#[no_mangle]
pub extern "C" fn get_result_norm_x_ptr() -> i32 {
    unsafe { RESULT_NORM_X.as_ptr() as i32 }
}

#[no_mangle]
pub extern "C" fn get_result_norm_y_ptr() -> i32 {
    unsafe { RESULT_NORM_Y.as_ptr() as i32 }
}

// ----- Coordinate math — mirrors MarkerTileRenderer.geoToTilePoint() exactly -----

fn geo_to_tile_point(lon: f64, lat: f64, n: f64) -> (f64, f64) {
    // Longitude → x, with wrap-around handling.
    let lon_wrapped = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    let x0 = ((lon_wrapped + 180.0) / 360.0) * n;
    let x = ((x0 % n) + n) % n;

    // Latitude → y via Web Mercator projection.
    let lat_clamped = lat.clamp(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT);
    let lat_rad = lat_clamped * PI / 180.0;
    let y = (1.0 - (lat_rad.tan() + 1.0 / lat_rad.cos()).ln() / PI) / 2.0 * n;
    let y_clamped = y.clamp(0.0, n - 1e-9);

    (x, y_clamped)
}
