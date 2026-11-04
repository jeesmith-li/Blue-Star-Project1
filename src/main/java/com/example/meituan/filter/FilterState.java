package com.example.meituan.filter;

/**
 * 筛选状态
 */
public class FilterState {
    private ReplyFilter replyFilter;
    private RatingFilter ratingFilter;
    private ContentFilter contentFilter;

    public enum ReplyFilter {
        ALL("全部"),
        UNREPLIED("未回复"),
        REPLIED("已回复"),
        UNKNOWN("未知");

        private final String text;
        ReplyFilter(String text) { this.text = text; }
        public String getText() { return text; }
    }

    public enum RatingFilter {
        ALL("全部"),
        GOOD("好评（含4-5星打分项）"),
        MEDIUM("中评（含3星打分项）"),
        BAD("差评（含1-2星打分项）"),
        UNKNOWN("未知");

        private final String text;
        RatingFilter(String text) { this.text = text; }
        public String getText() { return text; }
    }

    public enum ContentFilter {
        ALL("全部"),
        HAS_CONTENT("有内容"),
        NO_CONTENT("无内容"),
        UNKNOWN("未知");

        private final String text;
        ContentFilter(String text) { this.text = text; }
        public String getText() { return text; }
    }

    public FilterState() {
        this.replyFilter = ReplyFilter.UNKNOWN;
        this.ratingFilter = RatingFilter.UNKNOWN;
        this.contentFilter = ContentFilter.UNKNOWN;
    }

    public ReplyFilter getReplyFilter() {
        return replyFilter;
    }

    public void setReplyFilter(ReplyFilter replyFilter) {
        this.replyFilter = replyFilter;
    }

    public RatingFilter getRatingFilter() {
        return ratingFilter;
    }

    public void setRatingFilter(RatingFilter ratingFilter) {
        this.ratingFilter = ratingFilter;
    }

    public ContentFilter getContentFilter() {
        return contentFilter;
    }

    public void setContentFilter(ContentFilter contentFilter) {
        this.contentFilter = contentFilter;
    }

    public boolean isCompleted() {
        return replyFilter == ReplyFilter.UNREPLIED &&
               ratingFilter == RatingFilter.BAD &&
               contentFilter == ContentFilter.HAS_CONTENT;
    }

    @Override
    public String toString() {
        return String.format("FilterState[reply=%s, rating=%s, content=%s]",
                replyFilter, ratingFilter, contentFilter);
    }
}
