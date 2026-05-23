package cn.hollis.llm.mentor.agent;

import kotlin.jvm.Synchronized;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class test {

	public static void main(String[] args) {
		int[] nums = {2, 7, 11, 15};
		int target = 9;
		int[] result = twoSum(nums, target);
		System.out.println("数组: " + Arrays.toString(nums));
		System.out.println("目标值: " + target);
		System.out.println("结果: " + Arrays.toString(result));
	}
	public static int[] twoSum(int[] nums, int target) {
		for (int i = 0; i < nums.length; i++) {
			for (int j = i + 1; j < nums.length; j++) {
				if (nums[i] + nums[j] == target) {
					return new int[]{i, j};
				}
			}
		}
		return new int[]{-1, -1};
	}

}
