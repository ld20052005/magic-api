package org.ssssssss.magicapi.functions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.ssssssss.magicapi.cache.SqlCache;
import org.ssssssss.magicapi.config.DynamicDataSource;
import org.ssssssss.magicapi.dialect.Dialect;
import org.ssssssss.magicapi.dialect.DialectUtils;
import org.ssssssss.magicapi.exception.MagicAPIException;
import org.ssssssss.magicapi.model.Page;
import org.ssssssss.magicapi.model.PageResult;
import org.ssssssss.magicapi.provider.PageProvider;
import org.ssssssss.magicapi.provider.ResultProvider;
import org.ssssssss.script.MagicScriptContext;
import org.ssssssss.script.annotation.UnableCall;
import org.ssssssss.script.functions.StreamExtension;
import org.ssssssss.script.parsing.GenericTokenParser;
import org.ssssssss.script.parsing.Parser;
import org.ssssssss.script.parsing.TokenStream;
import org.ssssssss.script.parsing.Tokenizer;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DatabaseQuery extends HashMap<String, DatabaseQuery> {

	@UnableCall
	private DynamicDataSource dataSource;

	@UnableCall
	private JdbcTemplate template;

	@UnableCall
	private PageProvider pageProvider;

	@UnableCall
	private ResultProvider resultProvider;

	@UnableCall
	private RowMapper<Map<String, Object>> rowMapper;

	@UnableCall
	private SqlCache sqlCache;

	@UnableCall
	private String cacheName;

	@UnableCall
	private long ttl;

	public DatabaseQuery() {

	}

	public DatabaseQuery(DynamicDataSource dataSource) {
		this.dataSource = dataSource;
		this.template = dataSource.getJdbcTemplate();
	}

	@UnableCall
	public void setPageProvider(PageProvider pageProvider) {
		this.pageProvider = pageProvider;
	}

	@UnableCall
	public void setResultProvider(ResultProvider resultProvider) {
		this.resultProvider = resultProvider;
	}

	@UnableCall
	public void setRowMapper(RowMapper<Map<String, Object>> rowMapper) {
		this.rowMapper = rowMapper;
	}

	@UnableCall
	public void setDataSource(DynamicDataSource dataSource) {
		this.dataSource = dataSource;
	}

	@UnableCall
	public void setSqlCache(SqlCache sqlCache) {
		this.sqlCache = sqlCache;
	}

	@UnableCall
	public void setTemplate(JdbcTemplate template) {
		this.template = template;
	}

	@UnableCall
	public void setCacheName(String cacheName) {
		this.cacheName = cacheName;
	}

	@UnableCall
	public void setTtl(long ttl) {
		this.ttl = ttl;
	}

	@UnableCall
	public DatabaseQuery cloneQuery() {
		DatabaseQuery query = new DatabaseQuery();
		query.setDataSource(this.dataSource);
		query.setTemplate(this.template);
		query.setPageProvider(this.pageProvider);
		query.setRowMapper(this.rowMapper);
		query.setSqlCache(this.sqlCache);
		query.setTtl(this.ttl);
		query.setResultProvider(this.resultProvider);
		return query;
	}

	@UnableCall
	private <T> T putCacheValue(T value, BoundSql boundSql) {
		if (this.cacheName != null) {
			this.sqlCache.put(this.cacheName, boundSql.getCacheKey(this.sqlCache), value, this.ttl);
		}
		return value;
	}


	public DatabaseQuery cache(String cacheName, long ttl) {
		if (cacheName == null) {
			return this;
		}
		DatabaseQuery query = cloneQuery();
		query.setCacheName(cacheName);
		query.setTtl(ttl);
		return query;
	}

	public DatabaseQuery cache(String cacheName) {
		return cache(cacheName, 0);
	}

	@Override
	public DatabaseQuery get(Object key) {
		DatabaseQuery query = cloneQuery();
		if (key == null) {
			query.setTemplate(dataSource.getJdbcTemplate());
		} else {
			query.setTemplate(dataSource.getJdbcTemplate(key.toString()));
		}
		return query;
	}


	public List<Map<String, Object>> select(String sql) {
		BoundSql boundSql = new BoundSql(sql);
		return (List<Map<String, Object>>) boundSql.getCacheValue(this.sqlCache, this.cacheName)
				.orElseGet(() -> putCacheValue(template.query(boundSql.getSql(), this.rowMapper, boundSql.getParameters()), boundSql));
	}

	public int update(String sql) {
		BoundSql boundSql = new BoundSql(sql);
		int value = template.update(boundSql.getSql(), boundSql.getParameters());
		if (this.cacheName != null) {
			this.sqlCache.delete(this.cacheName);
		}
		return value;
	}

	public Object page(String sql) {
		Page page = pageProvider.getPage(MagicScriptContext.get());
		return page(sql, page.getLimit(), page.getOffset());
	}

	public Object page(String sql, long limit, long offset) {
		BoundSql boundSql = new BoundSql(sql);
		Connection connection = null;
		PageResult<Map<String, Object>> result = new PageResult<>();
		Dialect dialect;
		try {
			connection = template.getDataSource().getConnection();
			dialect = DialectUtils.getDialectFromUrl(connection.getMetaData().getURL());
		} catch (Exception e) {
			throw new MagicAPIException("自动获取数据库方言失败", e);
		} finally {
			DataSourceUtils.releaseConnection(connection, template.getDataSource());
		}
		if (dialect == null) {
			throw new MagicAPIException("自动获取数据库方言失败");
		}
		int count = (int) boundSql.getCacheValue(this.sqlCache, this.cacheName)
				.orElseGet(() -> putCacheValue(template.queryForObject(dialect.getCountSql(boundSql.getSql()), Integer.class, boundSql.getParameters()), boundSql));
		List<Object> list = null;
		if (count > 0) {
			String pageSql = dialect.getPageSql(boundSql.getSql(), boundSql, offset, limit);
			list = (List<Object>) boundSql.removeCacheKey().getCacheValue(this.sqlCache, this.cacheName)
					.orElseGet(() -> putCacheValue(template.query(pageSql, this.rowMapper, boundSql.getParameters()), boundSql));
		}
		return resultProvider.buildPageResult(count, list);
	}

	public Integer selectInt(String sql) {
		BoundSql boundSql = new BoundSql(sql);
		return (Integer) boundSql.getCacheValue(this.sqlCache, this.cacheName)
				.orElseGet(() -> putCacheValue(template.queryForObject(boundSql.getSql(), boundSql.getParameters(), Integer.class), boundSql));
	}

	public Map<String, Object> selectOne(String sql) {
		BoundSql boundSql = new BoundSql(sql);
		return (Map<String, Object>) boundSql.getCacheValue(this.sqlCache, this.cacheName)
				.orElseGet(() -> {
					List<Map<String, Object>> list = template.query(boundSql.getSql(), this.rowMapper, boundSql.getParameters());
					return list != null && list.size() > 0 ? list.get(0) : null;
				});
	}

	public Object selectValue(String sql) {
		BoundSql boundSql = new BoundSql(sql);
		return boundSql.getCacheValue(this.sqlCache, this.cacheName)
				.orElseGet(() -> putCacheValue(template.queryForObject(boundSql.getSql(), boundSql.getParameters(), Object.class), boundSql));
	}

	private static Tokenizer tokenizer = new Tokenizer();

	private static GenericTokenParser concatTokenParser = new GenericTokenParser("${", "}", false);

	private static GenericTokenParser replaceTokenParser = new GenericTokenParser("#{", "}", true);

	private static GenericTokenParser ifTokenParser = new GenericTokenParser("?{", "}", true);

	private static GenericTokenParser ifParamTokenParser = new GenericTokenParser("?{", ",", true);

	public static class BoundSql {
		private String sql;
		private List<Object> parameters = new ArrayList<>();
		private String cacheKey;


		BoundSql(String sql) {
			MagicScriptContext context = MagicScriptContext.get();
			this.sql = ifTokenParser.parse(sql.trim(), text -> {
				AtomicBoolean ifTrue = new AtomicBoolean(false);
				String val = ifParamTokenParser.parse("?{" + text, param -> {
					Object result = Parser.parseExpression(new TokenStream(tokenizer.tokenize(param))).evaluate(context);
					if (result != null) {
						if (result instanceof String) {
							ifTrue.set(!result.toString().isEmpty());
						} else {
							ifTrue.set(!Objects.equals(false, result));
						}
					}
					return null;
				});
				if (ifTrue.get()) {
					return val;
				}
				return "";
			});
			this.sql = concatTokenParser.parse(this.sql, text -> String.valueOf(Parser.parseExpression(new TokenStream(tokenizer.tokenize(text))).evaluate(context)));
			this.sql = replaceTokenParser.parse(this.sql, text -> {
				Object value = Parser.parseExpression(new TokenStream(tokenizer.tokenize(text))).evaluate(context);
				try {
					List<Object> objects = StreamExtension.arrayLikeToList(value);
					StringBuilder sb = new StringBuilder();
					for (int i = 0, size = objects.size(); i < size; i++) {
						sb.append("?");
						if (i + 1 < size) {
							sb.append(",");
						}
						parameters.add(objects.get(i));
					}
					return sb.toString();
				} catch (Exception e) {
					parameters.add(value);
					return "?";
				}
			});
		}

		public void addParameter(Object value) {
			parameters.add(value);
		}

		public String getSql() {
			return sql;
		}

		public Object[] getParameters() {
			return parameters.toArray();
		}

		public BoundSql removeCacheKey() {
			this.cacheKey = null;
			return this;
		}

		public String getCacheKey(SqlCache sqlCache) {
			if (cacheKey == null) {
				cacheKey = sqlCache.buildSqlCacheKey(this);
			}
			return cacheKey;
		}

		public <T> Optional<T> getCacheValue(SqlCache sqlCache, String cacheName) {
			return Optional.ofNullable(cacheName == null ? null : sqlCache.get(cacheName, getCacheKey(sqlCache)));
		}
	}

}
