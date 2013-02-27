package com.cm4j.test.guava.consist.usage;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.cm4j.test.guava.consist.ConcurrentCache;
import com.cm4j.test.guava.consist.SingleReference;
import com.cm4j.test.guava.consist.entity.TestTable;
import com.cm4j.test.guava.consist.usage.caches.TableAndNameCache;
import com.cm4j.test.guava.consist.usage.caches.TableAndNameVO;
import com.cm4j.test.guava.consist.usage.caches.TableIdCache;

/**
 * 1.缓存过期了不应该能修改状态? 使用引用队列？<br>
 * 2.单个对象的状态修改？ 不要放到应用中修改，放到SingleValue和ListValue中处理
 * 
 * @author Yang.hao
 * @since 2013-2-15 上午09:45:41
 * 
 */
@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = { "classpath*:test_1/spring-ds.xml" })
public class SingleTest {

	@Test
	public void getTest() {
		TestTable table = ConcurrentCache.getInstance().get(new TableIdCache(9999)).get();
		TestTable table2 = new TableIdCache(9999).reference().get();

		Assert.assertTrue(table == table2);
	}

	@Test
	public void saveOrUpdateTest() {
		SingleReference<TestTable> reference = ConcurrentCache.getInstance().get(new TableIdCache(3));
		TestTable testTable = reference.get();

		long old = 0;
		if (testTable == null) {
			// 新增时要注意：键要一致，要保证再从db查询新增的仍然能查询到
			testTable = new TestTable(3, (long) 1);
		} else {
			old = testTable.getNValue();
			testTable.setNValue(testTable.getNValue() + 1);
		}
		reference.update(testTable);

		Assert.assertEquals(old + 1, ConcurrentCache.getInstance().get(new TableIdCache(3)).get().getNValue()
				.longValue());
	}

	@Test
	public void deleteTest() {
		SingleReference<TestTable> reference = ConcurrentCache.getInstance().get(new TableIdCache(3));
		reference.delete();

		Assert.assertNull(ConcurrentCache.getInstance().get(new TableIdCache(3)).get());
	}

	@Test
	public void multiTableGetTest() {
		TableAndNameVO tableAndName = ConcurrentCache.getInstance().get(new TableAndNameCache(1)).get();
		Assert.assertNotNull(tableAndName.getName());
	}
	
	@Test
	public void persistAndRemove(){
		// todo
	}

	@Test
	public void t(){
		SingleReference<TestTable> reference = new TableIdCache(5).reference();
		TestTable testTable = reference.get();
		testTable.setNValue(1L);
		reference.update(testTable);
		testTable.setNValue(2L);
		reference.update(testTable);
	}
}