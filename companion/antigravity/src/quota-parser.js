// Keep only measured quotas. Account fields, prompts and OAuth credentials never
// enter a snapshot. Missing quota information is not equivalent to unused quota.
export function parseModelQuotas(response, nowEpochSeconds = Math.floor(Date.now() / 1000)) {
  const status = response?.userStatus;
  const models = status?.cascadeModelConfigData?.clientModelConfigs ?? response?.clientModelConfigs;
  if (!Array.isArray(models)) throw new Error('No model quotas available');
  const labels = new Set();
  const windows = [];
  for (const model of models) {
    const label = model?.label;
    const remaining = model?.quotaInfo?.remainingFraction;
    if (typeof label !== 'string' || !label.trim() || label.length > 64 || /[\x00-\x1f\x7f]/.test(label)) continue;
    if (typeof remaining !== 'number' || !Number.isFinite(remaining) || remaining < 0 || remaining > 1) continue;
    if (labels.has(label.toLowerCase())) continue;
    const reset = Date.parse(model.quotaInfo.resetTime);
    const resetsAtEpochSeconds = Math.floor(reset / 1000);
    // An expired backend window must not be presented as a freshly measured quota.
    if (Number.isFinite(reset) && resetsAtEpochSeconds < nowEpochSeconds - 120) continue;
    labels.add(label.toLowerCase());
    windows.push({
      label,
      usedFraction: 1 - remaining,
      ...(Number.isFinite(reset) && resetsAtEpochSeconds <= nowEpochSeconds + 31 * 86400
        ? { resetsAtEpochSeconds } : {})
    });
  }
  if (windows.length === 0 || windows.length > 32) throw new Error('No supported model quotas available');
  return { windows };
}
