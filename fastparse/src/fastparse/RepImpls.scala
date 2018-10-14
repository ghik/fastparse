package fastparse

import scala.annotation.tailrec
import scala.reflect.macros.blackbox.Context
import language.experimental.macros

/**
  * Implementations of the various `.rep`/`.repX` overloads. The most common
  * and simple overloads are implemented as macros for performance, while the
  * more complex/general cases are left as normal methods to avoid code bloat
  * and allow the use of default/named arguments (which don't work in macros
  * due to https://github.com/scala/bug/issues/5920).
  *
  * Even the normal method overloads are manually-specialized to some extent
  * for various sorts of inputs as a best-effort attempt ot minimize branching
  * in the hot paths.
  */
object RepImpls{
  def repXMacro0[T: c.WeakTypeTag, V: c.WeakTypeTag](c: Context)
                                                    (whitespace: Option[c.Tree], min: Option[c.Tree])
                                                    (repeater: c.Tree,
                                                     ctx: c.Tree): c.Tree = {
    import c.universe._
    val repeater1 = TermName(c.freshName("repeater"))
    val ctx1 = TermName(c.freshName("repeater"))
    val acc = TermName(c.freshName("acc"))
    val startIndex = TermName(c.freshName("startIndex"))
    val count = TermName(c.freshName("count"))
    val precut = TermName(c.freshName("precut"))
    val beforeSepIndex = TermName(c.freshName("beforeSepIndex"))
    val rec = TermName(c.freshName("rec"))
    val originalCut = TermName(c.freshName("originalCut"))
    val parsedMsg = TermName(c.freshName("parsedMsg"))
    val endSnippet = min match{
      case None =>
        q"""
          $ctx1.prepareSuccess($repeater1.result($acc), $parsedMsg() + ".rep", $startIndex, $originalCut)
        """
      case Some(min1) =>
        q"""
           if ($count < $min1) $ctx1.prepareFailure($parsedMsg() + ".rep", $startIndex, $originalCut)
           else $ctx1.prepareSuccess($repeater1.result($acc), $parsedMsg() + ".rep", $startIndex, $originalCut)
        """
    }

    val wsSnippet = whitespace match{
      case None => q"()"
      case Some(ws) =>
        q"""
        if ($ws ne fastparse.NoWhitespace.noWhitespaceImplicit) {
          val oldNoDropBuffer = $ctx1.noDropBuffer // completely disallow dropBuffer
          $ctx1.noDropBuffer = true
          $ws($ctx1)
          $ctx1.noDropBuffer = oldNoDropBuffer
        }
        $ctx1.cut = false
        """
    }

    q"""
      $ctx match{ case $ctx1 =>
        var $originalCut = $ctx1.cut
        val $repeater1 = $repeater
        val $acc = $repeater1.initial
        @_root_.scala.annotation.tailrec
        def $rec($startIndex: _root_.scala.Int,
                 $count: _root_.scala.Int,
                 $precut: _root_.scala.Boolean): _root_.fastparse.P[${c.weakTypeOf[V]}] = {
          $ctx1.cut = $precut

          ${c.prefix}.parse0()
          val $parsedMsg = $ctx1.shortFailureMsg
          $originalCut |= $ctx1.cut
          if (!$ctx1.isSuccess) {
            if ($ctx1.cut) $ctx1.asInstanceOf[fastparse.P[${c.weakTypeOf[V]}]]
            else $endSnippet
          }else {
            val $beforeSepIndex = $ctx1.index
            $repeater1.accumulate($ctx1.successValue.asInstanceOf[${c.weakTypeOf[T]}], $acc)
            $ctx1.cut = false
            $wsSnippet
            $rec($beforeSepIndex, $count + 1, false)
          }
        }
        $rec($ctx1.index, 0, false)
      }
    """
  }

  def repXMacro1[T: c.WeakTypeTag, V: c.WeakTypeTag](c: Context)
                                                    (repeater: c.Tree,
                                                     ctx: c.Tree): c.Tree = {
    import c.universe._
    RepImpls.repXMacro0[T, V](c)(None, None)(repeater, ctx)
  }

  def repXMacro2[T: c.WeakTypeTag, V: c.WeakTypeTag](c: Context)
                                                    (min: c.Tree)
                                                    (repeater: c.Tree,
                                                     ctx: c.Tree): c.Tree = {
    import c.universe._
    RepImpls.repXMacro0[T, V](c)(None, Some(min))(repeater, ctx)
  }

  def repXMacro1ws[T: c.WeakTypeTag, V: c.WeakTypeTag](c: Context)
                                                      (repeater: c.Tree,
                                                       whitespace: c.Tree,
                                                       ctx: c.Tree): c.Tree = {
    import c.universe._
    RepImpls.repXMacro0[T, V](c)(Some(whitespace), None)(repeater, ctx)
  }

  def repXMacro2ws[T: c.WeakTypeTag, V: c.WeakTypeTag](c: Context)
                                                      (min: c.Tree)
                                                      (repeater: c.Tree,
                                                       whitespace: c.Tree,
                                                       ctx: c.Tree): c.Tree = {
    import c.universe._
    RepImpls.repXMacro0[T, V](c)(Some(whitespace), Some(min))(repeater, ctx)
  }
}

class RepImpls[T](val parse0: () => ParsingRun[T]) extends AnyVal{
  def repX[V](min: Int = 0,
              sep: => ParsingRun[_] = null,
              max: Int = Int.MaxValue,
              exactly: Int = -1)
             (implicit repeater: Implicits.Repeater[T, V],
              ctx: ParsingRun[Any]): ParsingRun[V] = {

    val acc = repeater.initial
    val actualMin = if(exactly == -1) min else exactly
    val actualMax = if(exactly == -1) max else exactly
    var originalCut = ctx.cut
    def end(successIndex: Int, index: Int, count: Int, shortMsg: => String) = {
      if (count < actualMin) ctx.prepareFailure(shortMsg + ".rep", index, originalCut)
      else ctx.prepareSuccess(repeater.result(acc), shortMsg + ".rep", successIndex, originalCut)
    }
    @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): ParsingRun[V] = {
      ctx.cut = precut
      if (count == 0 && actualMax == 0) ctx.prepareSuccess(
        repeater.result(acc),
        "",
        startIndex
      )
      else {
        parse0()
        val parseMsg = ctx.shortFailureMsg
        originalCut |= ctx.cut
        if (!ctx.isSuccess) {
          if (ctx.cut) ctx.asInstanceOf[ParsingRun[V]]
          else end(startIndex, startIndex, count, parseMsg())
        }else {
          val beforeSepIndex = ctx.index
          repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
          val nextCount = count + 1
          if (nextCount == actualMax) end(beforeSepIndex, beforeSepIndex, nextCount, parseMsg())
          else {
            ctx.cut = false

            val sep1 = sep
            originalCut |= ctx.cut
            if (sep1 == null) rec(beforeSepIndex, nextCount, false)
            else {
              if (ctx.isSuccess) rec(beforeSepIndex, nextCount, ctx.cut)
              else if (ctx.cut) ctx.prepareFailure(parseMsg() + ".rep", beforeSepIndex, originalCut)
              else end(beforeSepIndex, beforeSepIndex, nextCount, parseMsg())
            }
          }
        }
      }
    }
    rec(ctx.index, 0, false)
  }
  def repX[V](min: Int,
              sep: => ParsingRun[_])
             (implicit repeater: Implicits.Repeater[T, V],
              ctx: ParsingRun[Any]): ParsingRun[V] = {
    var originalCut = ctx.cut
    val acc = repeater.initial
    def end(successIndex: Int, index: Int, count: Int, shortMsg: => String) = {
      if (count < min) ctx.prepareFailure(shortMsg + ".rep", index, originalCut)
      else ctx.prepareSuccess(repeater.result(acc), shortMsg + ".rep", successIndex, originalCut)
    }
    @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): ParsingRun[V] = {
      ctx.cut = precut
      parse0()
      val parsedMsg = ctx.shortFailureMsg
      originalCut |= ctx.cut
      if (!ctx.isSuccess) {
        if (ctx.cut) ctx.asInstanceOf[ParsingRun[V]]
        else end(startIndex, startIndex, count, parsedMsg())
      }else {
        val beforeSepIndex = ctx.index
        repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
        val nextCount = count + 1
        ctx.cut = false
        val sep1 = sep
        originalCut |= ctx.cut
        if (sep1 == null) rec(beforeSepIndex, nextCount, false)
        else {
          if (ctx.isSuccess) rec(beforeSepIndex, nextCount, ctx.cut)
          else if (ctx.cut) ctx.prepareFailure(parsedMsg() + ".rep", beforeSepIndex, ctx.cut | originalCut)
          else end(beforeSepIndex, beforeSepIndex, nextCount, parsedMsg())
        }
      }
    }
    rec(ctx.index, 0, false)
  }
  def rep[V](min: Int = 0,
             sep: => ParsingRun[_] = null,
             max: Int = Int.MaxValue,
             exactly: Int = -1)
            (implicit repeater: Implicits.Repeater[T, V],
             whitespace: ParsingRun[_] => ParsingRun[Unit],
             ctx: ParsingRun[Any]): ParsingRun[V] = {

    var originalCut = ctx.cut
    val acc = repeater.initial
    val actualMin = if(exactly == -1) min else exactly
    val actualMax = if(exactly == -1) max else exactly
    def end(successIndex: Int, index: Int, count: Int, shortMsg: => String) = {
      if (count < actualMin) ctx.prepareFailure(shortMsg + ".rep", index, originalCut)
      else ctx.prepareSuccess(repeater.result(acc), shortMsg + ".rep", successIndex, originalCut)
    }
    @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): ParsingRun[V] = {
      ctx.cut = precut
      if (count == 0 && actualMax == 0) ctx.prepareSuccess(repeater.result(acc), "", startIndex)
      else {
        parse0()
        val parsedMsg = ctx.shortFailureMsg
        originalCut |= ctx.cut
        if (!ctx.isSuccess){
          if (ctx.cut) ctx.asInstanceOf[ParsingRun[V]]
          else end(startIndex, startIndex, count, parsedMsg())
        }else{
          val beforeSepIndex = ctx.index
          repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
          val nextCount = count + 1
          if (nextCount == actualMax) end(beforeSepIndex, beforeSepIndex, nextCount, parsedMsg())
          else {
            if (whitespace ne NoWhitespace.noWhitespaceImplicit) whitespace(ctx)
            ctx.cut = false
            val sep1 = sep
            originalCut |= ctx.cut
            if (sep1 == null) rec(beforeSepIndex, nextCount, false)
            else if (ctx.isSuccess) {
              val sepCut = ctx.cut
              if (whitespace ne NoWhitespace.noWhitespaceImplicit) whitespace(ctx)
              rec(beforeSepIndex, nextCount, sepCut)
            }
            else if (ctx.cut) ctx.prepareFailure(parsedMsg() + ".rep", beforeSepIndex, originalCut)
            else end(beforeSepIndex, beforeSepIndex, nextCount, parsedMsg())
          }
        }
      }
    }
    rec(ctx.index, 0, false)
  }
  def rep[V](min: Int,
             sep: => ParsingRun[_])
            (implicit repeater: Implicits.Repeater[T, V],
             whitespace: ParsingRun[_] => ParsingRun[Unit],
             ctx: ParsingRun[Any]): ParsingRun[V] = {

    var originalCut = ctx.cut
    val acc = repeater.initial
    def end(successIndex: Int, index: Int, count: Int, shortMsg: => String) = {
      if (count < min) ctx.prepareFailure(shortMsg + ".rep", index, originalCut)
      else ctx.prepareSuccess(repeater.result(acc), shortMsg + ".rep", successIndex, originalCut)
    }
    @tailrec def rec(startIndex: Int, count: Int, precut: Boolean): ParsingRun[V] = {
      ctx.cut = precut
      parse0()
      val parsedMsg = ctx.shortFailureMsg
      originalCut |= ctx.cut
      if (!ctx.isSuccess){
        if (ctx.cut) ctx.asInstanceOf[ParsingRun[V]]
        else end(startIndex, startIndex, count, parsedMsg())
      }else{
        val beforeSepIndex = ctx.index
        repeater.accumulate(ctx.successValue.asInstanceOf[T], acc)
        val nextCount = count + 1
        if (whitespace ne NoWhitespace.noWhitespaceImplicit) {
          val oldCapturing = ctx.noDropBuffer // completely disallow dropBuffer
          ctx.noDropBuffer = true
          whitespace(ctx)
          ctx.noDropBuffer = oldCapturing
        }
        ctx.cut = false
        val sep1 = sep
        originalCut |= ctx.cut
        if (sep1 == null) rec(beforeSepIndex, nextCount, false)
        else if (ctx.isSuccess) {
          val sepCut = ctx.cut
          if (whitespace ne NoWhitespace.noWhitespaceImplicit) {
            val oldCapturing = ctx.noDropBuffer // completely disallow dropBuffer
            ctx.noDropBuffer = true
            whitespace(ctx)
            ctx.noDropBuffer = oldCapturing
          }
          rec(beforeSepIndex, nextCount, sepCut)
        }
        else if (ctx.cut) ctx.prepareFailure(parsedMsg() + ".rep", beforeSepIndex, ctx.cut | originalCut)
        else end(beforeSepIndex, beforeSepIndex, nextCount, parsedMsg())
      }
    }
    rec(ctx.index, 0, false)
  }

}