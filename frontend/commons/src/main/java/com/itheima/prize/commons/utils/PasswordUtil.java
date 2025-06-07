package com.itheima.prize.commons.utils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 密码工具类
 */
public class PasswordUtil {
	

	private String md5(String inputStr) {
		try {
			// 获取MD5 MessageDigest实例
			MessageDigest md = MessageDigest.getInstance("MD5");

			// 更新摘要以包含输入字符串的字节
			md.update(inputStr.getBytes(StandardCharsets.UTF_8));

			// 计算哈希值
			byte[] digest = md.digest();

			// 将字节数组转换为十六进制字符串
			BigInteger no = new BigInteger(1, digest);

			// 转换为无符号十六进制字符串并移除前缀"0x"
			String hashtext = no.toString(16);

			// 填充到32位长度，如果不足则前面补0
			while (hashtext.length() < 32) {
				hashtext = "0" + hashtext;
			}

			return hashtext;

		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 密码加密
	 */
	public static String encodePassword(String password) {
		return new PasswordUtil().md5(password);
	}
	
	public static void main(String[] args) {
		System.out.println(encodePassword("123456"));
	}

}
