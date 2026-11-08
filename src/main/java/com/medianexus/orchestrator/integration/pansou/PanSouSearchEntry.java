package com.medianexus.orchestrator.integration.pansou;

/** sizeBytes 为来源正文报告大小的近似字节数；缺失、歧义或冲突时为 null。 */
public record PanSouSearchEntry(
        String url,
        String password,
        String note,
        String datetime,
        String source,
        Long sizeBytes,
        boolean sizeConflict
) {
    public PanSouSearchEntry(String url, String password, String note, String datetime, String source) {
        this(url, password, note, datetime, source, null, false);
    }
    public PanSouSearchEntry(String url, String password, String note, String datetime, String source, Long sizeBytes) {
        this(url, password, note, datetime, source, sizeBytes, false);
    }
}
