package com.kakaopaycorp.api.domain.event.repository;

import org.springframework.stereotype.Repository;

import com.kakaopaycorp.api.domain.event.dto.RandomPushSearchDto;
import com.kakaopaycorp.api.domain.event.model.RandomPush;

@Repository
public class RandomPushRepository {

	public Integer save(RandomPush randomPush) {
		return null;
	}

	public RandomPush findBy(RandomPushSearchDto searchDto) {
		return null;
	}
}
