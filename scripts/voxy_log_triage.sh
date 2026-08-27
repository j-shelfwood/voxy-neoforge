#!/bin/bash
# Analyze Voxy-related log signals and print action-oriented triage output.
# Usage: ./scripts/voxy_log_triage.sh [/path/to/latest.log]

set -euo pipefail

LOG_FILE="${1:-latest.log}"
if [[ ! -f "$LOG_FILE" ]]; then
  echo "[ERROR] Log file not found: $LOG_FILE" >&2
  exit 1
fi

LC_ALL=C awk '
BEGIN {
  voxy_lines = 0
  voxy_warn_lines = 0
  missing_model_warns = 0
  inflight_summary_warns = 0
  large_copy_warns = 0
  gpu_wait_fail_warns = 0
  missing_model_max_misses = 0
  missing_model_max_unique = 0
  inflight_max_deferred = 0
  inflight_max_suppressed = 0
  inflight_max_pending_reruns = 0
  large_copy_warn_max = 0
  async_perf_lines = 0
  async_max_pending_copy_remaining = 0
  async_max_copy_batch = 0
  async_max_copy_dispatched = 0
  async_warn_threshold = 0
  async_max_inflight_deferred = 0
  async_max_rerun_executed = 0
  upload_perf_lines = 0
  upload_max_pending_copies = 0
  upload_max_queued_frames = 0
  upload_max_backpressure = 0
  upload_max_glfinish_stalls = 0
  world_cache_perf_lines = 0
  world_min_hit_pct = 0
  traversal_perf_lines = 0
  traversal_max_request_budget = 0
  traversal_max_mesh_queue = 0
  traversal_max_top_nodes = 0
  chunk_mask_perf_lines = 0
  chunk_mask_max_tracked = 0
  chunk_mask_max_pending_add = 0
  chunk_mask_max_pending_remove = 0
}

function num_after_equals(token,    a) {
  split(token, a, "=")
  return a[2] + 0
}

function extract_int_after(text, marker,    p, s, out, i, c) {
  p = index(text, marker)
  if (p == 0) return -1
  s = substr(text, p + length(marker))
  out = ""
  for (i = 1; i <= length(s); i++) {
    c = substr(s, i, 1)
    if (c ~ /[0-9]/) {
      out = out c
    } else if (length(out) > 0) {
      break
    }
  }
  if (length(out) == 0) return -1
  return out + 0
}

function print_header(title) {
  print ""
  print "== " title " =="
}

/\[Voxy\/\]/ {
  voxy_lines++
  if ($0 ~ /\/WARN\]/) voxy_warn_lines++
}

/\[Voxy\/\]: \[[^]]*RenderDataFactory\]: Missing model summary:/ {
  missing_model_warns++
  misses = extract_int_after($0, "Missing model summary: ")
  if (misses >= 0 && misses > missing_model_max_misses) missing_model_max_misses = misses
  uniq_ids = extract_int_after($0, "(")
  if (uniq_ids >= 0 && uniq_ids > missing_model_max_unique) missing_model_max_unique = uniq_ids
}

/\[Voxy\/\]: \[[^]]*NodeManager\]: Inflight request summary:/ {
  inflight_summary_warns++
  v = extract_int_after($0, "deferred=")
  if (v >= 0 && v > inflight_max_deferred) inflight_max_deferred = v
  v = extract_int_after($0, "suppressed=")
  if (v >= 0 && v > inflight_max_suppressed) inflight_max_suppressed = v
  v = extract_int_after($0, "pending_reruns=")
  if (v >= 0 && v > inflight_max_pending_reruns) inflight_max_pending_reruns = v
}

/\[Voxy\/\]: \[[^]]*AsyncNodeManager\]: Large amount of copies/ {
  large_copy_warns++
  v = extract_int_after($0, "happen: ")
  if (v >= 0 && v > large_copy_warn_max) large_copy_warn_max = v
}

/\[Voxy\/\]: \[[^]]*BasicSectionGeometryData\]: Failed to wait for gpu memory to be freed/ {
  gpu_wait_fail_warns++
}

/VOXY_PERF async_node/ {
  async_perf_lines++
  n = split($0, toks, /[[:space:]]+/)
  for (i = 1; i <= n; i++) {
    if (toks[i] ~ /^pending_copy_remaining=/) {
      v = num_after_equals(toks[i]); if (v > async_max_pending_copy_remaining) async_max_pending_copy_remaining = v
    } else if (toks[i] ~ /^max_copy_batch=/) {
      v = num_after_equals(toks[i]); if (v > async_max_copy_batch) async_max_copy_batch = v
    } else if (toks[i] ~ /^max_copy_dispatched_per_tick=/) {
      v = num_after_equals(toks[i]); if (v > async_max_copy_dispatched) async_max_copy_dispatched = v
    } else if (toks[i] ~ /^warn_threshold_copies=/) {
      async_warn_threshold = num_after_equals(toks[i])
    } else if (toks[i] ~ /^request_inflight_deferred=/) {
      v = num_after_equals(toks[i]); if (v > async_max_inflight_deferred) async_max_inflight_deferred = v
    } else if (toks[i] ~ /^request_rerun_executed=/) {
      v = num_after_equals(toks[i]); if (v > async_max_rerun_executed) async_max_rerun_executed = v
    }
  }
}

/VOXY_PERF upload_stream/ {
  upload_perf_lines++
  n = split($0, toks, /[[:space:]]+/)
  for (i = 1; i <= n; i++) {
    if (toks[i] ~ /^pending_copies=/) {
      v = num_after_equals(toks[i]); if (v > upload_max_pending_copies) upload_max_pending_copies = v
    } else if (toks[i] ~ /^queued_frames=/) {
      v = num_after_equals(toks[i]); if (v > upload_max_queued_frames) upload_max_queued_frames = v
    } else if (toks[i] ~ /^backpressure_observations=/) {
      v = num_after_equals(toks[i]); if (v > upload_max_backpressure) upload_max_backpressure = v
    } else if (toks[i] ~ /^glfinish_stalls=/) {
      v = num_after_equals(toks[i]); if (v > upload_max_glfinish_stalls) upload_max_glfinish_stalls = v
    }
  }
}

/VOXY_PERF world_section_cache/ {
  world_cache_perf_lines++
  n = split($0, toks, /[[:space:]]+/)
  for (i = 1; i <= n; i++) {
    if (toks[i] ~ /^hit_pct=/) {
      sub(/^hit_pct=/, "", toks[i])
      hit = toks[i] + 0.0
      if (world_min_hit_pct == 0 || hit < world_min_hit_pct) world_min_hit_pct = hit
    }
  }
}

/VOXY_PERF traversal/ {
  traversal_perf_lines++
  n = split($0, toks, /[[:space:]]+/)
  for (i = 1; i <= n; i++) {
    if (toks[i] ~ /^request_budget=/) {
      v = num_after_equals(toks[i]); if (v > traversal_max_request_budget) traversal_max_request_budget = v
    } else if (toks[i] ~ /^mesh_queue=/) {
      v = num_after_equals(toks[i]); if (v > traversal_max_mesh_queue) traversal_max_mesh_queue = v
    } else if (toks[i] ~ /^top_node_count=/) {
      v = num_after_equals(toks[i]); if (v > traversal_max_top_nodes) traversal_max_top_nodes = v
    }
  }
}

/VOXY_PERF chunk_mask/ {
  chunk_mask_perf_lines++
  n = split($0, toks, /[[:space:]]+/)
  for (i = 1; i <= n; i++) {
    if (toks[i] ~ /^tracked_sections=/) {
      v = num_after_equals(toks[i]); if (v > chunk_mask_max_tracked) chunk_mask_max_tracked = v
    } else if (toks[i] ~ /^pending_add=/) {
      v = num_after_equals(toks[i]); if (v > chunk_mask_max_pending_add) chunk_mask_max_pending_add = v
    } else if (toks[i] ~ /^pending_remove=/) {
      v = num_after_equals(toks[i]); if (v > chunk_mask_max_pending_remove) chunk_mask_max_pending_remove = v
    }
  }
}

END {
  print "Voxy Log Triage"
  print "  file: " FILENAME
  print "  voxy_lines: " voxy_lines
  print "  voxy_warn_lines: " (voxy_warn_lines + 0)

  print_header("Issue Summary")
  print "  missing_model_warnings: " (missing_model_warns + 0)
  print "  inflight_request_warnings: " (inflight_summary_warns + 0)
  print "  large_copy_warnings: " (large_copy_warns + 0)
  print "  gpu_wait_fail_warnings: " (gpu_wait_fail_warns + 0)

  print_header("Peaks")
  print "  missing_model_max_misses_5s: " (missing_model_max_misses + 0)
  print "  missing_model_max_unique_ids: " (missing_model_max_unique + 0)
  print "  inflight_max_deferred: " (inflight_max_deferred + 0)
  print "  inflight_max_pending_reruns: " (inflight_max_pending_reruns + 0)
  print "  large_copy_warn_max: " (large_copy_warn_max + 0)
  print "  async_max_pending_copy_remaining: " (async_max_pending_copy_remaining + 0)
  print "  async_max_copy_batch: " (async_max_copy_batch + 0)
  print "  async_warn_threshold: " (async_warn_threshold + 0)
  print "  upload_max_pending_copies: " (upload_max_pending_copies + 0)
  print "  upload_max_queued_frames: " (upload_max_queued_frames + 0)
  print "  upload_max_backpressure: " (upload_max_backpressure + 0)
  print "  upload_max_glfinish_stalls: " (upload_max_glfinish_stalls + 0)
  print "  world_min_hit_pct: " (world_min_hit_pct + 0)
  print "  traversal_max_request_budget: " (traversal_max_request_budget + 0)
  print "  traversal_max_mesh_queue: " (traversal_max_mesh_queue + 0)
  print "  traversal_max_top_nodes: " (traversal_max_top_nodes + 0)
  print "  chunk_mask_max_tracked_sections: " (chunk_mask_max_tracked + 0)
  print "  chunk_mask_max_pending_add: " (chunk_mask_max_pending_add + 0)
  print "  chunk_mask_max_pending_remove: " (chunk_mask_max_pending_remove + 0)

  print_header("Implementation Map")
  print "  Missing model summary -> src/main/java/me/cortex/voxy/client/core/rendering/building/RenderDataFactory.java"
  print "  Inflight request summary -> src/main/java/me/cortex/voxy/client/core/rendering/hierachical/NodeManager.java"
  print "  Large amount of copies -> src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java"
  print "  VOXY_PERF async_node -> src/main/java/me/cortex/voxy/client/core/rendering/hierachical/AsyncNodeManager.java"
  print "  VOXY_PERF upload_stream -> src/main/java/me/cortex/voxy/client/core/rendering/util/UploadStream.java"
  print "  VOXY_PERF traversal -> src/main/java/me/cortex/voxy/client/core/rendering/hierachical/HierarchicalOcclusionTraverser.java"
  print "  VOXY_PERF chunk_mask -> src/main/java/me/cortex/voxy/client/core/rendering/ChunkBoundRenderer.java"
  print "  GPU wait fail -> src/main/java/me/cortex/voxy/client/core/rendering/section/geometry/BasicSectionGeometryData.java"

  print_header("Recommended Actions")
  if (missing_model_warns > 0) {
    print "  [A1] High priority: investigate missing model IDs in your mod/resource-pack stack; fallback model rendering can look like stalled detail."
    print "       Action: capture first-seen missing IDs and map back to block states in RenderDataFactory/ModelFactory path."
  } else {
    print "  [A1] No missing model warnings detected."
  }

  if (inflight_summary_warns > 0 || async_max_inflight_deferred > 0 || inflight_max_pending_reruns > 0) {
    print "  [A2] Medium priority: monitor request deferral pressure (NodeManager)."
    print "       Action: if deferred rises continuously or pending_reruns stays > 0 for long periods, add per-pos aging logs and force rerun safeguards."
  } else {
    print "  [A2] No inflight request pressure detected."
  }

  if (large_copy_warns > 0 || (async_warn_threshold > 0 && async_max_copy_batch > async_warn_threshold)) {
    print "  [A3] Medium priority: copy bursts exceeded comfort threshold."
    print "       Action: tune async copy budget/burst caps or spread copy dispatch across more ticks."
  } else {
    print "  [A3] Copy burst warnings not detected."
  }

  if (gpu_wait_fail_warns > 0) {
    print "  [A4] Medium priority: GPU memory release wait failed at least once."
    print "       Action: lower geometry capacity and retest; verify GPU driver version stability."
  } else {
    print "  [A4] No GPU memory release wait failures detected."
  }

  if (upload_max_backpressure > 0 || upload_max_glfinish_stalls > 0 || upload_max_pending_copies > 0) {
    print "  [A5] Upload pressure present."
    print "       Action: inspect UploadStream thresholds and staging sizes."
  } else {
    print "  [A5] Upload stream looks healthy (no backpressure/stalls/pending copies)."
  }

  if (traversal_perf_lines > 0) {
    print "  [A6] Traversal telemetry present."
    print "       Action: compare request_budget, top_node_count, and mesh_queue growth during movement spikes."
  } else {
    print "  [A6] No traversal telemetry detected."
  }

  if (chunk_mask_perf_lines > 0) {
    print "  [A7] Chunk mask telemetry present."
    print "       Action: verify tracked_sections and pending add/remove spikes against chunk transition churn."
  } else {
    print "  [A7] No chunk mask telemetry detected."
  }
}
' "$LOG_FILE"
