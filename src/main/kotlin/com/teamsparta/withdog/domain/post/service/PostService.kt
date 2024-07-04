package com.teamsparta.withdog.domain.post.service

import com.teamsparta.withdog.domain.comment.service.CommentService
import com.teamsparta.withdog.domain.like.service.LikeService
import com.teamsparta.withdog.domain.post.dto.*
import com.teamsparta.withdog.domain.post.model.Post
import com.teamsparta.withdog.domain.post.repository.PostRepository
import com.teamsparta.withdog.domain.user.repository.UserRepository
import com.teamsparta.withdog.global.exception.ModelNotFoundException
import com.teamsparta.withdog.global.exception.UnauthorizedException
import com.teamsparta.withdog.infra.s3.S3Service
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

@Service
class PostService(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
    private val s3Service: S3Service,
    private val likeService: LikeService,
    private val commentService: CommentService
)
{


    @Cacheable(value = ["popularPostCache"], key = "'getPopularPostList'") // 심히 테스트가 필요한 대목
    fun getPopularPostList()
    : List<PopularPostResponse>
    {
        val popularPosts = postRepository.findTop10ByIsDeletedFalseOrderByViewsDesc()
        return popularPosts.map { PopularPostResponse.from(it) }
    }



    fun getPostList(
        page: Int,
        size: Int,
        sortBy: String,
        direction: String
    ): PageResponse<PostResponse>
    {
        val direction = getDirection(direction)
        val pageable: Pageable = PageRequest.of(page, size, direction, sortBy)

        val pageContent = postRepository.findByIsDeletedFalseAndPageable(pageable)

        return PageResponse(pageContent.content.map {PostResponse.from(it, commentService.getCommentList(it.id!!))}, page, size)
    }

    @Cacheable(value = ["postCache"], key = "#postId")
    fun getPostById(
        postId: Long
    ): PostResponse
    {
        val post = postRepository.findByIdOrNull(postId)
            ?: throw ModelNotFoundException("삭제된 게시글 입니다.")
        if (post.isDeleted) throw ModelNotFoundException("삭제된 게시글 입니다.")

        post.views +=1
        postRepository.save(post) // TODO() 컨트롤러에서 증가시켜볼까
        return PostResponse.from(post, commentService.getCommentList(postId))
    }

    @Transactional
    fun createPost(
        userId: Long,
        postRequest: PostRequest,
        image: MultipartFile?
    ): PostResponse
    {
        val user = userRepository.findByIdOrNull(userId)
            ?: throw ModelNotFoundException("없는 사용자 입니다.")
        val fileUrl = image?.let { s3Service.upload(it) }

        return PostResponse.from(postRepository.save(postRequest.toEntity(user, fileUrl)), null)
    }

    @CachePut(value = ["postCache"], key = "#postId")
    @Transactional
    fun updatePost(
        postId: Long,
        userId: Long,
        postRequest: PostRequest,
        image: MultipartFile?
    ): PostResponse
    {
        val post = postRepository.findByIdOrNull(postId)
            ?: throw ModelNotFoundException("삭제된 게시글 입니다.")

        if (post.isDeleted)
            throw ModelNotFoundException("삭제된 게시글 입니다.")

        if(userId != post.user.id)
            throw UnauthorizedException("권한이 없습니다.")

        post.imageUrl?.let { s3Service.delete(it.split("m/")[1]) }
        val imageUrl = image?.let { s3Service.upload(it) }

        post.updatePost(postRequest, imageUrl)
        return PostResponse.from(post, commentService.getCommentList(postId))
    }

    @CacheEvict(value = ["postCache"], key = "#postId")
    @Transactional
    fun deletePost(
        postId: Long,
        userId: Long
    )
    {
        val post = postRepository.findByIdOrNull(postId)
            ?: throw ModelNotFoundException("삭제된 게시글 입니다.")

        if (post.isDeleted)
            throw ModelNotFoundException("삭제된 게시글 입니다.")

        if(userId != post.user.id)
            throw UnauthorizedException("권한이 없습니다.")

        post.imageUrl?.let { s3Service.delete(it.split("m/")[1]) }
        post.softDeleted()
        likeService.deleteLike(post)
    }

    fun postLike(
        postId: Long,
        userId: Long
    )
    {
        val post = postRepository.findByIdOrNull(postId)
            ?: throw ModelNotFoundException("삭제된 게시글입니다.")

        if (post.isDeleted)
            throw ModelNotFoundException("삭제된 게시글 입니다.")

        likeService.updateLike(userId, post)
    }

    private fun getDirection(sort: String) = when (sort)
    {
        "asc" -> Sort.Direction.ASC
        else -> Sort.Direction.DESC
    }
}