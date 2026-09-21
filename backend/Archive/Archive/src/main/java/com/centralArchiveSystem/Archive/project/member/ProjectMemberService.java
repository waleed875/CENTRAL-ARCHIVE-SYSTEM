package com.centralArchiveSystem.Archive.project.member;

import com.centralArchiveSystem.Archive.common.exception.ResourceNotFoundException;
import com.centralArchiveSystem.Archive.project.member.dto.MemberRequest;
import com.centralArchiveSystem.Archive.project.member.dto.MemberResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ProjectMemberService {

    private final ProjectMemberRepository projectMemberRepository;

    public ProjectMemberService(ProjectMemberRepository projectMemberRepository) {
        this.projectMemberRepository = projectMemberRepository;
    }

    public List<MemberResponse> listByProjectId(UUID projectId) {
        return projectMemberRepository.findByProjectId(projectId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public MemberResponse getById(UUID id) {
        ProjectMember member = projectMemberRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectMember", "id", id));
        return toResponse(member);
    }

    @Transactional
    public void copyMembers(UUID sourceProjectId, UUID targetProjectId) {
        List<ProjectMember> copies = projectMemberRepository.findByProjectId(sourceProjectId).stream()
                .map(source -> {
                    ProjectMember copy = new ProjectMember();
                    copy.setProjectId(targetProjectId);
                    copy.setPersonName(source.getPersonName());
                    copy.setOrganizationName(source.getOrganizationName());
                    copy.setRoleName(source.getRoleName());
                    copy.setNotes(source.getNotes());
                    return copy;
                })
                .toList();

        if (!copies.isEmpty()) {
            projectMemberRepository.saveAll(copies);
        }
    }

    @Transactional
    public MemberResponse create(UUID projectId, MemberRequest request) {
        ProjectMember member = new ProjectMember();
        member.setProjectId(projectId);
        member.setPersonName(request.personName());
        member.setOrganizationName(request.organizationName());
        member.setRoleName(request.roleName());
        member.setNotes(request.notes());

        ProjectMember saved = projectMemberRepository.save(member);
        return toResponse(saved);
    }

    @Transactional
    public MemberResponse update(UUID projectId, UUID id, MemberRequest request) {
        ProjectMember member = projectMemberRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectMember", "id", id));

        if (!member.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("ProjectMember", "id", id);
        }

        if (request.personName() != null) member.setPersonName(request.personName());
        if (request.organizationName() != null) member.setOrganizationName(request.organizationName());
        if (request.roleName() != null) member.setRoleName(request.roleName());
        if (request.notes() != null) member.setNotes(request.notes());

        ProjectMember saved = projectMemberRepository.save(member);
        return toResponse(saved);
    }

    @Transactional
    public void delete(UUID projectId, UUID id) {
        ProjectMember member = projectMemberRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProjectMember", "id", id));

        if (!member.getProjectId().equals(projectId)) {
            throw new ResourceNotFoundException("ProjectMember", "id", id);
        }

        projectMemberRepository.delete(member);
    }

    private MemberResponse toResponse(ProjectMember member) {
        return new MemberResponse(
                member.getId(),
                member.getProjectId(),
                member.getPersonName(),
                member.getOrganizationName(),
                member.getRoleName(),
                member.getNotes(),
                member.getCreatedAt()
        );
    }
}
