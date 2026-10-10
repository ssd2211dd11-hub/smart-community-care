package org.imnu.smartcommunitycare.catalog.model.request;
import lombok.Getter;
import lombok.Setter;
import org.imnu.smartcommunitycare.common.api.PageQuery;
@Getter @Setter
public class CatalogPageQuery extends PageQuery {private String keyword;private Boolean enabled;private String category;}
