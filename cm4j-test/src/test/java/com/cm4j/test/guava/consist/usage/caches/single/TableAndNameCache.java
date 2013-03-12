package com.cm4j.test.guava.consist.usage.caches.single;

import java.util.List;

import org.apache.commons.lang.math.NumberUtils;

import com.cm4j.dao.hibernate.HibernateDao;
import com.cm4j.test.guava.consist.ServiceManager;
import com.cm4j.test.guava.consist.SingleReference;
import com.cm4j.test.guava.consist.entity.TestName;
import com.cm4j.test.guava.consist.entity.TestTable;
import com.cm4j.test.guava.consist.loader.CacheDescriptor;
import com.cm4j.test.guava.consist.usage.caches.vo.TableAndNameVO;
import com.google.common.base.Preconditions;

public class TableAndNameCache extends CacheDescriptor<SingleReference<TableAndNameVO>> {

	public TableAndNameCache(Object... params) {
		super(params);
	}

	@SuppressWarnings("rawtypes")
	@Override
	public SingleReference<TableAndNameVO> load(String... params) {
		Preconditions.checkArgument(params.length == 1);
		HibernateDao hibernate = ServiceManager.getInstance().getSpringBean("hibernateDao");

		String hql = "from TestTable t1, TestName t2 where t1.NId = t2.NId and t1.NId = ?";
		List all = hibernate.findAll(hql, NumberUtils.toInt(params[0]));
		Object[] vlaue = (Object[]) all.get(0);
		TestTable testTable = (TestTable) vlaue[0];
		TestName testName = (TestName) vlaue[1];
		TableAndNameVO result = new TableAndNameVO();
		result.setId(testTable.getNId());
		result.setName(testName.getSName());
		result.setValue(testTable.getNValue());
		return new SingleReference<TableAndNameVO>(result);
	}

}