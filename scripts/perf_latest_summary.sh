#!/bin/bash
# Summarize VOXY_PERF entries from latest.log-style files.
# Usage: ./scripts/perf_latest_summary.sh [/path/to/latest.log]

set -euo pipefail

LOG_FILE="${1:-latest.log}"
if [[ ! -f "$LOG_FILE" ]]; then
  echo "[ERROR] Log file not found: $LOG_FILE" >&2
  exit 1
fi

LC_ALL=C awk '
function reset_map(   k) { for (k in map) delete map[k] }
function load_map(line,   i,n,a,kv) {
  reset_map()
  n = split(line, a, /[[:space:]]+/)
  for (i = 1; i <= n; i++) {
    if (index(a[i], "=") > 0) {
      split(a[i], kv, "=")
      map[kv[1]] = kv[2]
    }
  }
}
/VOXY_PERF upload_stream/ { upload_line = $0 }
/VOXY_PERF world_section_cache/ { section_line = $0 }
/VOXY_PERF async_node/ { async_line = $0 }
/VOXY_PERF traversal/ { traversal_line = $0 }
/VOXY_PERF chunk_mask/ { chunk_mask_line = $0 }
/\[VoxyDiag\] chunkBoundary/ { chunk_boundary_line = $0 }
END {
  if (upload_line == "" && section_line == "" && async_line == "" && traversal_line == "" && chunk_mask_line == "" && chunk_boundary_line == "") {
    print "[INFO] No VOXY_PERF lines found in " FILENAME
    exit 0
  }

  if (upload_line != "") {
    load_map(upload_line)
    print "[UPLOAD_STREAM]"
    print "  coherent=" map["coherent"]
    print "  remaining_bytes=" map["remaining_bytes"]
    print "  threshold_bytes=" map["threshold_bytes"]
    print "  used_bytes=" map["used_bytes"]
    print "  queued_frames=" map["queued_frames"]
    print "  pending_copies=" map["pending_copies"]
    print "  glfinish_stalls=" map["glfinish_stalls"]
    print "  backpressure_observations=" map["backpressure_observations"]
  }

  if (section_line != "") {
    load_map(section_line)
    print "[WORLD_SECTION_CACHE]"
    print "  pool_size=" map["pool_size"]
    print "  allocations=" map["allocations"]
    print "  hits=" map["hits"]
    print "  misses=" map["misses"]
    print "  hit_pct=" map["hit_pct"]
    print "  offers=" map["offers"]
    print "  rejects=" map["rejects"]
  }

  if (async_line != "") {
    load_map(async_line)
    print "[ASYNC_NODE]"
    print "  copy_batches=" map["copy_batches"]
    print "  avg_copy_batch=" map["avg_copy_batch"]
    print "  max_copy_batch=" map["max_copy_batch"]
    print "  copy_dispatch_batches=" map["copy_dispatch_batches"]
    print "  avg_copy_dispatched_per_tick=" map["avg_copy_dispatched_per_tick"]
    print "  max_copy_dispatched_per_tick=" map["max_copy_dispatched_per_tick"]
    print "  pending_copy_remaining=" map["pending_copy_remaining"]
    print "  pending_result_age_ticks=" map["pending_result_age_ticks"]
    print "  result_copy_entries=" map["result_copy_entries"]
    print "  result_scatter_entries=" map["result_scatter_entries"]
    print "  copy_budget_per_tick=" map["copy_budget_per_tick"]
    print "  chunked_copy_enabled=" map["chunked_copy_enabled"]
    print "  sync_wait_events=" map["sync_wait_events"]
    print "  sync_wait_threshold_copies=" map["sync_wait_threshold_copies"]
    print "  warn_threshold_copies=" map["warn_threshold_copies"]
  }

  if (traversal_line != "") {
    load_map(traversal_line)
    print "[TRAVERSAL]"
    print "  interval_frames=" map["interval_frames"]
    print "  executed=" map["executed"]
    print "  request_budget=" map["request_budget"]
    print "  top_node_count=" map["top_node_count"]
    print "  mesh_queue=" map["mesh_queue"]
    print "  current_max_node_id=" map["current_max_node_id"]
    print "  camera_distance_culling=" map["camera_distance_culling"]
    print "  visibility_culling=" map["visibility_culling"]
    print "  viewport=" map["viewport"]
  }

  if (chunk_mask_line != "") {
    load_map(chunk_mask_line)
    print "[CHUNK_MASK]"
    print "  tracked_sections=" map["tracked_sections"]
    print "  pending_add=" map["pending_add"]
    print "  pending_remove=" map["pending_remove"]
    print "  configured_render_distance_chunks=" map["configured_render_distance_chunks"]
    print "  offset_blocks=" map["offset_blocks"]
    print "  boundary_buffer_blocks=" map["boundary_buffer_blocks"]
    print "  render_distance_blocks=" map["render_distance_blocks"]
    print "  camera=" map["camera"]
    print "  viewport=" map["viewport"]
  }

  if (chunk_boundary_line != "") {
    load_map(chunk_boundary_line)
    print "[CHUNK_BOUNDARY]"
    print "  tracked_columns=" map["tracked_columns"]
    print "  draw_spans=" map["draw_spans"]
    print "  evaluated_spans=" map["evaluated_spans"]
    print "  inside_chunk_tracking=" map["inside_chunk_tracking"]
    print "  inside_sphere=" map["inside_sphere"]
    print "  inside_cylinder=" map["inside_cylinder"]
    print "  inside_xz_only=" map["inside_xz_only"]
    print "  chunk_tracking_not_cylinder=" map["chunk_tracking_not_cylinder"]
    print "  chunk_tracking_not_xz_only=" map["chunk_tracking_not_xz_only"]
    print "  xz_only_not_cylinder=" map["xz_only_not_cylinder"]
    print "  xz_only_not_chunk_tracking=" map["xz_only_not_chunk_tracking"]
    print "  cylinder_not_sphere=" map["cylinder_not_sphere"]
    print "  sphere_not_cylinder=" map["sphere_not_cylinder"]
    print "  max_vertical_excess=" map["max_vertical_excess"]
    print "  max_sphere_margin=" map["max_sphere_margin"]
    print "  samples=" map["samples"]
  }
}
' "$LOG_FILE"
