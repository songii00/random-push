package com.kakaopaycorp.api.domain.event.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import com.kakaopaycorp.api.domain.event.dto.RandomPushRequestDto;
import com.kakaopaycorp.api.domain.event.dto.RandomPushRequestDto.Search;
import com.kakaopaycorp.api.domain.event.dto.RandomPushRequestDto.Status.PublishedInfo;
import com.kakaopaycorp.api.domain.event.model.RandomPush;
import com.kakaopaycorp.api.domain.event.model.RandomPushDetail;
import com.kakaopaycorp.api.domain.event.repository.RandomPushDetailRepository;
import com.kakaopaycorp.api.domain.event.repository.RandomPushRepository;
import com.kakaopaycorp.api.global.cache.RedisPrefix;
import com.kakaopaycorp.api.global.cache.annotation.RedisCacheEvict;
import com.kakaopaycorp.api.global.cache.annotation.RedisCacheable;

@Service
public class RandomPushService {

	private final RandomPushRepository randomPushRepository;
	private final RandomPushDetailRepository randomPushDetailRepository;

	public RandomPushService(RandomPushRepository randomPushRepository,
							 RandomPushDetailRepository randomPushDetailRepository) {
		this.randomPushRepository = randomPushRepository;
		this.randomPushDetailRepository = randomPushDetailRepository;
	}

	/**
	 * 뿌리기 저장
	 *
	 * @param requestDto
	 */
	@Transactional
	public void save(RandomPushRequestDto requestDto) {

		requestDto.setToken(this.getHashKeyBy(requestDto.getToken()));
		Integer randomPushNo = randomPushRepository.save(requestDto.toEntity());

		List<RandomPushDetail> randomPushDetailes = getRandomPushDetail(requestDto);
		randomPushDetailes.forEach(detail -> detail.setRandomPushNo(randomPushNo));
		randomPushDetailRepository.save(randomPushDetailes);
	}

	/**
	 * 뿌리기 금액 분배
	 *
	 * @param requestDto
	 * @return
	 */
	private List<RandomPushDetail> getRandomPushDetail(RandomPushRequestDto requestDto) {
		int totalPushPrice = requestDto.getTotalPushPrice();
		int userCount = requestDto.getUserCount();
		List<Integer> randomPrices = getRandomPrices(totalPushPrice, userCount);

		return randomPrices.stream().map(randomPrice -> RandomPushDetail.builder()
																		.publishedPrice(randomPrice)
																		.registDateTime(LocalDateTime.now())
																		.registUserId(requestDto.getUserId())
																		.build()).collect(Collectors.toList());
	}

	/**
	 * 랜덤 금액 구하기
	 *
	 * @param totalPushPrice
	 * @param userCount
	 * @return
	 */
	private List<Integer> getRandomPrices(int totalPushPrice, int userCount) {

		ArrayList<Integer> randomPrices = new ArrayList<>();
		for (int i = 0; i < userCount; i++) {

			if (i == userCount - 1) {
				randomPrices.add(totalPushPrice);
				continue;
			}

			int price = ((int) (Math.random() * (totalPushPrice / userCount)) + 11);
			price = price - (price % 10);
			totalPushPrice -= price;
			randomPrices.add(price);
		}
		return randomPrices;
	}

	/**
	 * 상태 조회
	 *
	 * @param requestDto
	 * @return
	 */
	public RandomPushRequestDto.Status getRandomPushStatus(RandomPushRequestDto requestDto) {
		RandomPush randomPush = getRandomPush(new Search(requestDto.getToken(), requestDto.getRoomId(), null));

		if (validateStatus(requestDto, randomPush)) {
			throw new IllegalArgumentException("validation fail");
		}

		List<RandomPushDetail> details = randomPush.getDetails();
		List<PublishedInfo> publishedInfos
				= details.stream()
						 .filter(RandomPushDetail::isUseYn)
						 .map(detail -> new PublishedInfo(detail.getPublishedPrice(),
														  detail.getRegistUserId()))
						 .collect(Collectors.toList());

		return RandomPushRequestDto.Status.builder()
										  .pushTime(randomPush.getRegistDateTime())
										  .pushPrice(randomPush.getPushPrice())
										  .publishedPrice(randomPush.getPublishedPrice())
										  .publishedInfos(publishedInfos)
										  .build();
	}

	/**
	 * 상태 조회 검증
	 *
	 * @param requestDto
	 * @param randomPush
	 * @return
	 */
	private boolean validateStatus(RandomPushRequestDto requestDto, RandomPush randomPush) {
		// 뿌린 사람 자신만 조회
		if (!randomPush.getRegistUserId().equals(requestDto.getUserId())) {
			return false;
		}

		// 뿌린 건에 대해 7일간 조회
		if (LocalDateTime.now().minusDays(7).isAfter(randomPush.getRegistDateTime())) {
			return false;
		}
		return true;
	}

	@RedisCacheable(prefix = RedisPrefix.RANDOM_PUSH, ids = {"@search#token", "@search#roomId"})
	public RandomPush getRandomPush(Search search) {
		return randomPushRepository.findBy(search);
	}

	/**
	 * 뿌리기 검증
	 *
	 * @param existRandomPush
	 * @param randomPush
	 * @return
	 */
	public boolean validatePublish(RandomPush existRandomPush, RandomPush randomPush) {

		// 등록 사용자와 동일한 사용자인지 검증
		if (existRandomPush.getRegistUserId().equals(randomPush.getRegistUserId())) {
			return false;
		}
		List<RandomPushDetail> usedRandomPushDetails =
				existRandomPush.getDetails().stream()
							   .filter(RandomPushDetail::isUseYn)
							   .collect(Collectors.toList());

		// 사용할 수 있는 받기 목록이 없음
		if (CollectionUtils.isEmpty(usedRandomPushDetails)) {
			return false;
		}

		long matchedUserCount = usedRandomPushDetails.stream()
													 .filter(detail -> detail.getPublishUserId().equals(randomPush.getRegistUserId()))
													 .count();

		// 뿌리기당 사용자는 한번만 받을 수 있음
		if (matchedUserCount > 0) {
			return false;
		}

		// 동일 대화방의 사용자만 받을 수 있음
		if (!randomPush.getRoomId().equals(existRandomPush.getRoomId())) {
			return false;
		}

		return true;
	}


	/**
	 * 뿌리기 발급
	 *
	 * @param existRandomPush
	 * @param randomPush
	 * @return
	 */
	@Transactional
	public Integer publish(RandomPush existRandomPush, RandomPush randomPush) {
		List<RandomPushDetail> details = existRandomPush.getDetails();

		List<RandomPushDetail> usableDetails = details.stream()
													  .filter(detail -> !detail.isUseYn())
													  .collect(Collectors.toList());
		RandomPushDetail detail = usableDetails.get(0);

		detail.publish(randomPush.getRegistUserId());

		randomPushDetailRepository.save(detail);
		randomPushRepository.increasePublishedPrice(detail.getPublishedPrice());

		return detail.getPublishedPrice();
	}

	@RedisCacheEvict(prefix = RedisPrefix.RANDOM_PUSH, ids = {"@randomPush#token", "@randomPush#roomId"})
	public void deleteRandomPushCache(RandomPush randomPush) {
		// 기존 캐시 삭제
	}

	/**
	 * 뿌린 건 유효시간 만료 체크
	 *
	 * @param randomPush
	 * @return
	 */
	public boolean isExpired(RandomPush randomPush) {
		return LocalDateTime.now().minusMinutes(10).isAfter(randomPush.getRegistDateTime());
	}

	/**
	 * 토큰 발급
	 *
	 * @return
	 */
	public String publishToken() {
		return TokenKeygen.publishToken();
	}

	/**
	 * 해쉬키 변경
	 *
	 * @param token
	 * @return
	 */
	private String getHashKeyBy(String token) {
		return TokenKeygen.getHashKeyBy(token);
	}
}
