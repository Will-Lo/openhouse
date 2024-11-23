package com.linkedin.openhouse.spark.sql.catalyst.parser.extensions

import com.linkedin.openhouse.spark.sql.catalyst.enums.{GrantableResourceTypes, LogicalOperators}
import com.linkedin.openhouse.spark.sql.catalyst.parser.extensions.OpenhouseSqlExtensionsParser._
import com.linkedin.openhouse.spark.sql.catalyst.plans.logical.{GrantRevokeStatement, SetColumnPolicyTag, SetRetentionPolicy, SetSharingPolicy, SetSnapshotsRetentionPolicy, ShowGrantsStatement}
import com.linkedin.openhouse.spark.sql.catalyst.enums.GrantableResourceTypes.GrantableResourceType
import com.linkedin.openhouse.spark.sql.catalyst.enums.LogicalOperators.LogicalOperatorsType
import com.linkedin.openhouse.gen.tables.client.model.TimePartitionSpec
import org.antlr.v4.runtime.tree.ParseTree
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan

import scala.collection.JavaConverters._

class OpenhouseSqlExtensionsAstBuilder (delegate: ParserInterface) extends OpenhouseSqlExtensionsBaseVisitor[AnyRef] {
  override def visitSingleStatement(ctx: SingleStatementContext): LogicalPlan = {
    typedVisit[LogicalPlan](ctx.statement)
  }

  override def visitSetRetentionPolicy(ctx: SetRetentionPolicyContext): SetRetentionPolicy = {
    val tableName = typedVisit[Seq[String]](ctx.multipartIdentifier)
    val retentionPolicy = ctx.retentionPolicy()
    val (granularity, count) = typedVisit[(String, Int)](retentionPolicy)
    val (colName, colPattern) =
      if (ctx.columnRetentionPolicy() != null)
        typedVisit[(String, String)](ctx.columnRetentionPolicy())
      else (null, null)
    SetRetentionPolicy(tableName, granularity, count, Option(colName), Option(colPattern))
  }

  override def visitSetSharingPolicy(ctx: SetSharingPolicyContext): SetSharingPolicy = {
    val tableName = typedVisit[Seq[String]](ctx.multipartIdentifier)
    val sharing = typedVisit[String](ctx.sharingPolicy())
    SetSharingPolicy(tableName, sharing)
  }

  override def visitSetColumnPolicyTag(ctx: SetColumnPolicyTagContext): SetColumnPolicyTag = {
    val tableName = typedVisit[Seq[String]](ctx.multipartIdentifier)
    val colName = ctx.columnNameClause().identifier().getText
    val policyTags = typedVisit[Seq[String]](ctx.columnPolicy())
    SetColumnPolicyTag(tableName, colName, policyTags)
  }

  override def visitGrantStatement(ctx: GrantStatementContext): GrantRevokeStatement = {
    val (resourceType, resourceName) = typedVisit[(GrantableResourceType, Seq[String])](ctx.grantableResource())
    val principal = typedVisit[String](ctx.principal)
    val privilege = typedVisit[String](ctx.privilege)
    GrantRevokeStatement(isGrant = true, resourceType, resourceName, privilege, principal)
  }

  override def visitRevokeStatement(ctx: RevokeStatementContext): GrantRevokeStatement = {
    val (resourceType, resourceName) = typedVisit[(GrantableResourceType, Seq[String])](ctx.grantableResource())
    val privilege = typedVisit[String](ctx.privilege)
    val principal = typedVisit[String](ctx.principal)
    GrantRevokeStatement(isGrant = false, resourceType, resourceName, privilege, principal)
  }

  override def visitShowGrantsStatement(ctx: ShowGrantsStatementContext): ShowGrantsStatement = {
    val (resourceType, resourceName) = typedVisit[(GrantableResourceType, Seq[String])](ctx.grantableResource())
    ShowGrantsStatement(resourceType, resourceName)
  }

  override def visitPrincipal(ctx: PrincipalContext): String = {
    ctx.getText
  }

  override def visitPrivilege(ctx: PrivilegeContext): String = {
    ctx.getText.toUpperCase
  }

  override def visitGrantableResource(ctx: GrantableResourceContext): (GrantableResourceType, Seq[String]) = {
    val resourceName = typedVisit[Seq[String]](ctx.multipartIdentifier())
    val resourceType = if (ctx.DATABASE != null) {
      GrantableResourceTypes.DATABASE
    } else if (ctx.TABLE != null) {
      GrantableResourceTypes.TABLE
    } else {
      throw new IllegalStateException("Unrecognized grantable resource: " +  ctx.getText)
    }
    (resourceType, resourceName)
  }

  override def visitMultipartIdentifier(ctx: MultipartIdentifierContext): Seq[String] = {
    toSeq(ctx.parts).map(_.getText)
  }

  override def visitRetentionPolicy(ctx: RetentionPolicyContext): (String, Int) = {
    typedVisit[(String, Int)](ctx.duration())
  }

  override def visitColumnRetentionPolicy(ctx: ColumnRetentionPolicyContext): (String, String) = {
    if (ctx.columnRetentionPolicyPatternClause() != null) {
      (ctx.columnNameClause().identifier().getText(), ctx.columnRetentionPolicyPatternClause().retentionColumnPatternClause().STRING().getText)
    } else {
      (ctx.columnNameClause().identifier().getText(), new String())
    }
  }

  override def visitColumnRetentionPolicyPatternClause(ctx: ColumnRetentionPolicyPatternClauseContext): String = {
    ctx.retentionColumnPatternClause().STRING().getText
  }

  override def visitSharingPolicy(ctx: SharingPolicyContext): String = {
    ctx.BOOLEAN().getText
  }

  override def visitColumnPolicy(ctx: ColumnPolicyContext): Seq[String] = {
    if (ctx.NONE() == null) {
      typedVisit[Seq[String]](ctx.multiTagIdentifier());
    } else {
      Seq.empty
    }
  }

  override def visitMultiTagIdentifier(ctx: MultiTagIdentifierContext): Seq[String] = {
    toSeq(ctx.policyTag()).map(_.getText)
  }

  override def visitSetSnapshotsRetentionPolicy(ctx: SetSnapshotsRetentionPolicyContext): SetSnapshotsRetentionPolicy = {
    val tableName = typedVisit[Seq[String]](ctx.multipartIdentifier)
    val (logicalOperator, granularity, timeCount, count) = typedVisit[(Option[LogicalOperatorsType], Option[String], Int, Int)](ctx.snapshotsRetentionPolicy())
    SetSnapshotsRetentionPolicy(tableName, logicalOperator, granularity, timeCount, count)
  }
  override def visitSnapshotsRetentionPolicy(ctx: SnapshotsRetentionPolicyContext): (Option[LogicalOperatorsType], Option[String], Int, Int) = {
    val logicalOperator = if (ctx.AND_OR_LOGICAL_OPERATOR() != null)
        LogicalOperators.withName(ctx.AND_OR_LOGICAL_OPERATOR().getText)
      else null
    val timePolicy = if (ctx.snapshotsTTL() != null)
        typedVisit[(String, Int)](ctx.snapshotsTTL())
      else (null, -1)
    val countPolicy = if (ctx.snapshotsCount() != null)
        typedVisit[Int](ctx.snapshotsCount())
      else -1
    (Option(logicalOperator), Option(timePolicy._1), timePolicy._2, countPolicy)
  }

  override def visitSnapshotsTTL(ctx: SnapshotsTTLContext): (String, Int) = {
    val granularity = ctx.dateGranularity().getText
    val count = ctx.POSITIVE_INTEGER().getText.toInt
    (granularity, count)
  }

  override def visitSnapshotsCount(ctx: SnapshotsCountContext): Integer = {
    ctx.POSITIVE_INTEGER().getText.toInt
  }

  private def toBuffer[T](list: java.util.List[T]) = list.asScala
  private def toSeq[T](list: java.util.List[T]) = toBuffer(list).toSeq

  private def typedVisit[T](ctx: ParseTree): T = {
    ctx.accept(this).asInstanceOf[T]
  }
}
